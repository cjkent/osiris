package io.github.cjkent.osiris.maven

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import io.github.cjkent.osiris.aws.Stage
import io.github.cjkent.osiris.aws.bucketName
import io.github.cjkent.osiris.aws.cloudformation.deployStack
import io.github.cjkent.osiris.aws.cloudformation.staticFilesBucketName
import io.github.cjkent.osiris.aws.cloudformation.writeTemplate
import io.github.cjkent.osiris.aws.createBucket
import io.github.cjkent.osiris.aws.deployStages
import io.github.cjkent.osiris.aws.uploadFile
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.server.ApiFactory
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Parent of the Osiris Mojo classes; contains common configuration parameters used by all subclasses.
 */
abstract class OsirisMojo : AbstractMojo() {

    @Parameter(required = true)
    protected lateinit var apiName: String

    @Parameter(required = true)
    protected lateinit var apiDefinitionClass: String

    @Parameter(required = true)
    protected lateinit var componentsClass: String

    @Parameter
    protected var environmentVariables: Map<String, String>? = null

    @Parameter
    protected var lambdaMemorySize: Int = 512

    @Parameter
    protected var lambdaTimeout: Int = 3

    @Parameter
    protected var codeBucket: String? = null

    @Parameter
    protected var staticFilesBucket: String? = null

    @Parameter
    protected var role: String? = null

    @Parameter
    protected var cloudFormationTemplate: File? = null

    @Parameter
    protected var stages: Map<String, StageConfig> = mapOf()

    @Component
    protected lateinit var project: MavenProject
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining the deployment goal; deploys an API and lambda function to AWS.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : OsirisMojo() {

    @Parameter(required = true)
    private lateinit var region: String

    @Parameter(property = "awsProfile")
    private var awsProfile: String? = null

    @Parameter
    private var staticFilesDirectory: String? = null

    // This is the directory {project}/target/classes
    @Parameter(property = "project.build.outputDirectory")
    private lateinit var builtResourcesDirectory: File

    private val staticBucket: String get() = staticFilesBucket ?: staticFilesBucketName(project.groupId, apiName)

    // this needs to be computed in the getter because it depends on lateinit vars that aren't initialised immediately
    private val templateFile: Path get() = cloudFormationTemplate?.toPath() ?: templateFile(project, apiName)

    override fun execute() {
        val jarName = jarFileName(project)
        val jarFile = Paths.get(project.build.directory).resolve(jarName)
        if (!Files.exists(jarFile)) throw MojoFailureException("Cannot find $jarName")
        val classLoader = URLClassLoader(arrayOf(jarFile.toUri().toURL()), javaClass.classLoader)
        val apiFactory = apiFactory(project, classLoader, componentsClass, apiDefinitionClass)
        if (!Files.exists(templateFile)) throw MojoFailureException("Cannot find $templateFile")
        deploy(jarFile, templateFile, apiFactory.api)
    }

    // TODO this logic should be pushed down into the AWS module. that will make Gradle support easier
    @Suppress("UNCHECKED_CAST")
    private fun deploy(jarFile: Path, templateFile: Path, api: Api<*>) {
        // This is required because the default chain doesn't use the AWS_DEFAULT_PROFILE environment variable
        // So if you want to use a non-default profile you have to use a different provider
        val credentialsProvider = if (awsProfile == null) {
            log.info("Using default credentials provider chain")
            DefaultAWSCredentialsProviderChain()
        } else {
            log.info("Using profile credentials provider with profile name '$awsProfile'")
            ProfileCredentialsProvider(awsProfile)
        }
        val codeBucket = this.codeBucket ?: createBucket(credentialsProvider, region, project.groupId, apiName, "code")
        val (_, jarKey) = jarS3Key(project, apiName)
        log.info("Uploading function code '$jarFile' to $codeBucket with key $jarKey")
        uploadFile(jarFile, codeBucket, region, credentialsProvider, jarKey)
        log.info("Upload of function code complete")
        val templateUrl = uploadFile(templateFile, codeBucket, region, credentialsProvider)
        log.debug("Uploaded template file ${templateFile.toAbsolutePath()}, S3 URL: $templateUrl")
        val deployResult = deployStack(region, credentialsProvider, apiName, templateUrl)
        uploadStaticFiles(api, credentialsProvider, staticBucket)
        val stages = this.stages.map { (name, config) -> config.toStage(name) }
        val apiId = deployResult.apiId
        val stackCreated = deployResult.stackCreated
        val deployedStages = deployStages(credentialsProvider, region, apiId, apiName, stages, stackCreated)
        for (stage in deployedStages) {
            log.info("Deployed to stage '$stage' at https://$apiId.execute-api.$region.amazonaws.com/$stage/")
        }
    }

    private fun uploadStaticFiles(api: Api<*>, credentialsProvider: AWSCredentialsProvider, bucket: String) {
        if (api.staticFiles) {
            val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?:
                builtResourcesDirectory.toPath().resolve("static")
            Files.walk(staticFilesDir, Int.MAX_VALUE)
                .filter { !Files.isDirectory(it) }
                .forEach { uploadFile(it, bucket, region, credentialsProvider, staticFilesDir) }
        }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generate a CloudFormation template using the API definition and additional configuration.
 */
@Mojo(name = "generate-cloudformation", defaultPhase = LifecyclePhase.PACKAGE)
class GenerateCloudFormationMojo : OsirisMojo() {

    override fun execute() {
        val apiFactory = apiFactory(project, javaClass.classLoader, componentsClass, apiDefinitionClass)
        val codeBucket = this.codeBucket ?: codeBucketName(project.groupId, apiName)
        val stages = this.stages.map { (name, config) -> config.toStage(name) }
        val environmentVars = this.environmentVariables ?: mapOf()
        val (hash, jarKey) = jarS3Key(project, apiName)
        val templateFile = templateFile(project, apiName)
        Files.deleteIfExists(templateFile)
        Files.createDirectories(templateFile.parent)
        Files.newBufferedWriter(templateFile, StandardOpenOption.CREATE).use {
            writeTemplate(
                it,
                apiFactory.api,
                apiName,
                project.groupId,
                "Created with Osiris",
                lambdaMemorySize,
                lambdaTimeout,
                hash,
                apiFactory.componentsClass,
                apiFactory.apiDefinitionClass,
                codeBucket,
                jarKey,
                role,
                staticFilesBucket,
                stages,
                environmentVars)
        }
    }
}

//--------------------------------------------------------------------------------------------------


/** Configuration for an API Gateway stage. */
data class StageConfig(
    var variables: Map<String, String> = mapOf(),
    var deployOnUpdate: Boolean = false,
    var description: String = ""
) {
    fun toStage(name: String) = Stage(name, variables, deployOnUpdate, description)
}

private fun apiFactory(
    project: MavenProject,
    parentClassLoader: ClassLoader,
    componentsClass: String,
    apiDefinitionClass: String
): ApiFactory<ComponentsProvider> {

    val jarFile = "${project.build.directory}/${project.artifactId}-${project.version}-jar-with-dependencies.jar"
    val jarPath = Paths.get(jarFile)
    if (!Files.exists(jarPath)) throw MojoFailureException("Cannot find $jarFile")
    val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), parentClassLoader)
    return ApiFactory.create(classLoader, componentsClass, apiDefinitionClass)
}

private fun codeBucketName(groupId: String, apiName: String): String = bucketName(groupId, apiName, "code")

private fun jarFileName(project: MavenProject): String =
    "${project.artifactId}-${project.version}-jar-with-dependencies.jar"

private data class JarKey(val hash: String, val name: String)

private fun jarS3Key(project: MavenProject, apiName: String): JarKey {
    val jarFileName = jarFileName(project)
    val jarPath = Paths.get(project.build.directory).resolve(jarFileName)
    val md5Hash = md5Hash(jarPath)
    return JarKey(md5Hash, "$apiName.$md5Hash.jar")
}

private fun templateName(apiName: String): String = "$apiName.template"

private fun templateFile(project: MavenProject, apiName: String): Path {
    val templateDir = Paths.get(project.build.directory).resolve("cloudformation")
    return templateDir.resolve(templateName(apiName))
}

private fun md5Hash(file: Path): String {
    val messageDigest = MessageDigest.getInstance("md5")
    val buffer = ByteArray(1024 * 1024)

    tailrec fun readChunk(stream: InputStream) {
        val bytesRead = stream.read(buffer)
        if (bytesRead == -1) {
            return
        } else {
            messageDigest.update(buffer, 0, bytesRead)
            readChunk(stream)
        }
    }
    Files.newInputStream(file).buffered(1024 * 1024).use { readChunk(it) }
    val digest = messageDigest.digest()
    return digest.joinToString("") { String.format("%02x", it) }
}
