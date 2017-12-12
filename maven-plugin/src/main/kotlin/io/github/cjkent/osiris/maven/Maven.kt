package io.github.cjkent.osiris.maven

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import io.github.cjkent.osiris.awsdeploy.Stage
import io.github.cjkent.osiris.awsdeploy.cloudformation.deployStack
import io.github.cjkent.osiris.awsdeploy.cloudformation.writeTemplate
import io.github.cjkent.osiris.awsdeploy.codeBucketName
import io.github.cjkent.osiris.awsdeploy.createBucket
import io.github.cjkent.osiris.awsdeploy.deployStages
import io.github.cjkent.osiris.awsdeploy.staticFilesBucketName
import io.github.cjkent.osiris.awsdeploy.uploadFile
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ApiFactory
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
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
    internal lateinit var apiName: String

    @Parameter(defaultValue = "\${project.groupId}")
    internal lateinit var rootPackage: String

    @Parameter(required = true)
    internal lateinit var apiProperty: String

    @Parameter(required = true)
    internal lateinit var componentsFunction: String

    @Parameter
    internal var environmentVariables: Map<String, String>? = null

    @Parameter
    internal var lambdaMemorySize: Int = 512

    @Parameter
    internal var lambdaTimeout: Int = 3

    @Parameter
    internal var codeBucket: String? = null

    @Parameter
    internal var staticFilesBucket: String? = null

    @Parameter
    internal var stages: Map<String, StageConfig> = mapOf()

    @Component
    internal lateinit var project: MavenProject

    internal val sourceDirectory: Path get() = Paths.get(project.build.sourceDirectory).parent

    internal val cloudFormationSourceDir: Path get() = sourceDirectory.resolve("cloudformation")

    internal val rootTemplate: Path get() = cloudFormationSourceDir.resolve("root.template")

    private val generatedCorePackage: String get() = "$rootPackage.core.generated"

    private val lambdaClassName: String get() = "$generatedCorePackage.GeneratedLambda"

    internal val lambdaHandler: String get() = "$lambdaClassName::handle"

    internal val apiFactoryClassName: String get() = "$generatedCorePackage.GeneratedApiFactory"
}

//--------------------------------------------------------------------------------------------------

abstract class GenerateMojo : OsirisMojo() {

    internal fun generate(fileNameRoot: String, generatedPackage: String) {
        // TODO validate these
        val apiProperty = this.apiProperty.replace("::", ".")
        val componentsFunction = this.componentsFunction.replace("::", ".")
        val templateStream = javaClass.getResourceAsStream("/$fileNameRoot.kt.txt")
        val templateText = BufferedReader(InputStreamReader(templateStream, Charsets.UTF_8)).readText()
        val generatedFile = templateText
            .replace("\${package}", "$rootPackage.$generatedPackage")
            .replace("\${api}", apiProperty)
            .replace("\${components}", componentsFunction)
        val generatedPackageDirs = rootPackage.split('.') + generatedPackage + "generated"
        val generatedRootDir = Paths.get(project.build.directory).resolve("generated-sources").resolve("osiris")
        val generatedDir = generatedPackageDirs.fold(generatedRootDir, { dir, pkg -> dir.resolve(pkg) })
        val generatedFilePath = generatedDir.resolve("$fileNameRoot.kt")
        Files.createDirectories(generatedDir)
        Files.write(generatedFilePath, generatedFile.toByteArray(Charsets.UTF_8))
        log.info("Generated sources to ${generatedFilePath.toAbsolutePath()}")
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generates a subclass of ProxyLambda that runs the web app in AWS.
 */
@Mojo(name = "generate-lambda", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
class GenerateLambdaMojo : GenerateMojo() {

    override fun execute() {
        generate("GeneratedLambda", "core")
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generates a `main` function to run the API in a local Jetty server.
 */
@Mojo(name = "generate-local-server", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
class GenerateLocalServerMojo : GenerateMojo() {

    override fun execute() {
        generate("GeneratedLocalServer", "localserver")
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generate a CloudFormation template using the API definition and additional configuration.
 */
@Mojo(name = "generate-cloudformation", defaultPhase = LifecyclePhase.PACKAGE)
class GenerateCloudFormationMojo : OsirisMojo() {

    @Parameter
    private var cognitoUserPoolArn: String? = null

    override fun execute() {
        val api = createApi(apiFactoryClassName, project, javaClass.classLoader)
        val codeBucket = this.codeBucket ?: codeBucketName(project.groupId, apiName)
        val stages = this.stages.map { (name, config) -> config.toStage(name) }
        val environmentVars = this.environmentVariables ?: mapOf()
        val (hash, jarKey) = jarS3Key(project, apiName)
        val templateFile = generatedTemplate(project, apiName)
        val lambdaHandler = lambdaHandler
        val createLambdaRole = !Files.exists(rootTemplate)
        Files.deleteIfExists(templateFile)
        Files.createDirectories(templateFile.parent)
        Files.newBufferedWriter(templateFile, StandardOpenOption.CREATE).use {
            writeTemplate(
                it,
                api,
                apiName,
                project.groupId,
                "Created with Osiris",
                lambdaHandler,
                lambdaMemorySize,
                lambdaTimeout,
                hash,
                codeBucket,
                jarKey,
                createLambdaRole,
                staticFilesBucket,
                cognitoUserPoolArn,
                stages,
                environmentVars)
        }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining the deployment goal; deploys an API and lambda function to AWS.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : OsirisMojo() {

    @Parameter(required = true)
    private lateinit var region: String

    @Parameter
    private var staticFilesDirectory: String? = null

    private val staticBucket: String get() = staticFilesBucket ?: staticFilesBucketName(project.groupId, apiName)

    override fun execute() {
        val jarName = jarFileName(project)
        val jarFile = Paths.get(project.build.directory).resolve(jarName)
        if (!Files.exists(jarFile)) throw MojoFailureException("Cannot find $jarName")
        val classLoader = URLClassLoader(arrayOf(jarFile.toUri().toURL()), javaClass.classLoader)
        val api = createApi(apiFactoryClassName, project, classLoader)
        deploy(jarFile, api)
    }

    // TODO this logic should be pushed down into the AWS module. that will make Gradle support easier
    @Suppress("UNCHECKED_CAST")
    private fun deploy(jarFile: Path, api: Api<*>) {
        val credentialsProvider = DefaultAWSCredentialsProviderChain()
        val codeBucket = this.codeBucket ?: createBucket(credentialsProvider, region, project.groupId, apiName, "code")
        val (_, jarKey) = jarS3Key(project, apiName)
        log.info("Uploading function code '$jarFile' to $codeBucket with key $jarKey")
        uploadFile(jarFile, codeBucket, region, credentialsProvider, jarKey)
        log.info("Upload of function code complete")
        uploadTemplates(codeBucket, credentialsProvider)
        val deploymentTemplateUrl = if (Files.exists(rootTemplate)) {
            templateUrl(rootTemplate.fileName.toString(), codeBucket, region)
        } else {
            templateUrl(generatedTemplateName(apiName), codeBucket, region)
        }
        val deployResult = deployStack(region, credentialsProvider, apiName, deploymentTemplateUrl)
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
            val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDirectory.resolve("static")
            Files.walk(staticFilesDir, Int.MAX_VALUE)
                .filter { !Files.isDirectory(it) }
                .forEach { uploadFile(it, bucket, region, credentialsProvider, staticFilesDir) }
        }
    }

    private fun uploadTemplates(codeBucket: String, credentialsProvider: AWSCredentialsProvider) {
        if (!Files.exists(cloudFormationSourceDir)) return
        Files.list(cloudFormationSourceDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { templateFile ->
                val templateUrl = uploadFile(templateFile, codeBucket, region, credentialsProvider)
                log.debug("Uploaded template file ${templateFile.toAbsolutePath()}, S3 URL: $templateUrl")
            }
        val generatedTemplate = generatedTemplate(project, apiName)
        val templateUrl = uploadFile(generatedTemplate, codeBucket, region, credentialsProvider)
        log.debug("Uploaded generated template file ${generatedTemplate.toAbsolutePath()}, S3 URL: $templateUrl")
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

//--------------------------------------------------------------------------------------------------

private fun createApi(apiFactoryClassName: String, project: MavenProject, parentClassLoader: ClassLoader): Api<*> {
    val jarFile = "${project.build.directory}/${project.artifactId}-${project.version}-jar-with-dependencies.jar"
    val jarPath = Paths.get(jarFile)
    if (!Files.exists(jarPath)) throw MojoFailureException("Cannot find $jarFile")
    val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), parentClassLoader)
    val apiFactoryClass = Class.forName(apiFactoryClassName, true, classLoader)
    val apiFactory = apiFactoryClass.newInstance() as ApiFactory<*>
    return apiFactory.api
}

private fun jarFileName(project: MavenProject): String =
    "${project.artifactId}-${project.version}-jar-with-dependencies.jar"

private data class JarKey(val hash: String, val name: String)

private fun jarS3Key(project: MavenProject, apiName: String): JarKey {
    val jarFileName = jarFileName(project)
    val jarPath = Paths.get(project.build.directory).resolve(jarFileName)
    val md5Hash = md5Hash(jarPath)
    return JarKey(md5Hash, "$apiName.$md5Hash.jar")
}

private fun generatedTemplateName(apiName: String): String = "$apiName.template"

private fun generatedTemplate(project: MavenProject, apiName: String): Path =
    Paths.get(project.build.directory).resolve("cloudformation").resolve(generatedTemplateName(apiName))

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

private fun templateUrl(templateName: String, codeBucket: String, region: String): String =
    "https://s3-$region.amazonaws.com/$codeBucket/$templateName"
