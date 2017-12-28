package io.github.cjkent.osiris.maven

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.github.cjkent.osiris.aws.ApiFactory
import io.github.cjkent.osiris.aws.ApplicationConfig
import io.github.cjkent.osiris.awsdeploy.cloudformation.deployStack
import io.github.cjkent.osiris.awsdeploy.cloudformation.writeTemplate
import io.github.cjkent.osiris.awsdeploy.codeBucketName
import io.github.cjkent.osiris.awsdeploy.createBucket
import io.github.cjkent.osiris.awsdeploy.deployStages
import io.github.cjkent.osiris.awsdeploy.staticFilesBucketName
import io.github.cjkent.osiris.awsdeploy.uploadFile
import io.github.cjkent.osiris.core.Api
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.BufferedReader
import java.io.FileReader
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

    @Parameter(defaultValue = "\${project.groupId}")
    internal lateinit var rootPackage: String

    @Parameter(required = true)
    internal lateinit var apiProperty: String

    @Parameter(required = true)
    internal lateinit var componentsFunction: String

    @Parameter(required = true)
    internal lateinit var configProperty: String

    @Component
    internal lateinit var project: MavenProject

    internal val sourceDirectory: Path get() = Paths.get(project.build.sourceDirectory).parent

    internal val cloudFormationSourceDir: Path get() = sourceDirectory.resolve("cloudformation")

    internal val rootTemplate: Path get() = cloudFormationSourceDir.resolve("root.template")

    private val generatedCorePackage: String get() = "$rootPackage.core.generated"

    private val lambdaClassName: String get() = "$generatedCorePackage.GeneratedLambda"

    internal val lambdaHandler: String get() = "$lambdaClassName::handle"

    internal val cloudFormationGeneratedDir: Path get() = Paths.get(project.build.directory).resolve("cloudformation")

    private val apiFactoryClassName: String get() = "$generatedCorePackage.GeneratedApiFactory"

    internal fun createApiFactory(parentClassLoader: ClassLoader): ApiFactory<*> {
        val jarFile = "${project.build.directory}/${project.artifactId}-${project.version}-jar-with-dependencies.jar"
        val jarPath = Paths.get(jarFile)
        if (!Files.exists(jarPath)) throw MojoFailureException("Cannot find $jarFile")
        val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), parentClassLoader)
        val apiFactoryClass = Class.forName(apiFactoryClassName, true, classLoader)
        return apiFactoryClass.newInstance() as ApiFactory<*>
    }

    internal fun generatedTemplatePath(applicationName: String): Path =
        cloudFormationGeneratedDir.resolve(generatedTemplateName(applicationName))
}

//--------------------------------------------------------------------------------------------------

abstract class GenerateMojo : OsirisMojo() {

    internal fun generate(fileNameRoot: String, generatedPackage: String) {
        // TODO validate these
        val apiProperty = this.apiProperty.replace("::", ".")
        val configProperty = this.configProperty.replace("::", ".")
        val componentsFunction = this.componentsFunction.replace("::", ".")
        val templateStream = javaClass.getResourceAsStream("/$fileNameRoot.kt.txt")
        val templateText = BufferedReader(InputStreamReader(templateStream, Charsets.UTF_8)).use { it.readText() }
        val generatedFile = templateText
            .replace("\${package}", "$rootPackage.$generatedPackage")
            .replace("\${api}", apiProperty)
            .replace("\${components}", componentsFunction)
            .replace("\${appConfig}", configProperty)
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
 *
 * Generating files in the package phase doesn't feel quite right. But the API must be instantiated to build
 * the CloudFormation template. In order to safely instantiate the API we need all the dependencies available.
 * The easiest way to do this is to use the distribution jar which is only built during packaging.
 */
@Mojo(name = "generate-cloudformation", defaultPhase = LifecyclePhase.PACKAGE)
class GenerateCloudFormationMojo : OsirisMojo() {

    override fun execute() {
        val apiFactory = createApiFactory(javaClass.classLoader)
        val api = apiFactory.api
        val appConfig = apiFactory.config
        val codeBucket = appConfig.codeBucket ?: codeBucketName(appConfig.applicationName)
        val (hash, jarKey) = jarS3Key(project, appConfig.applicationName)
        val lambdaHandler = lambdaHandler
        val rootTemplateExists = Files.exists(rootTemplate)
        val templateParams = if (rootTemplateExists) {
            // Parse the parameters from root.template and pass them to the lambda as env vars
            // This allows the handler code to reference any resources defined in root.template
            generatedTemplateParameters(rootTemplate, codeBucket, appConfig.applicationName)
        } else {
            setOf()
        }
        val createLambdaRole = !rootTemplateExists
        val generatedTemplatePath = generatedTemplatePath(appConfig.applicationName)
        Files.deleteIfExists(generatedTemplatePath)
        Files.createDirectories(generatedTemplatePath.parent)
        Files.newBufferedWriter(generatedTemplatePath, StandardOpenOption.CREATE).use {
            writeTemplate(it, api, appConfig, templateParams, lambdaHandler, hash, codeBucket, jarKey, createLambdaRole)
        }
        // copy all templates from the template src dir to the generated template dir with filtering
        if (!Files.exists(cloudFormationSourceDir)) return
        Files.list(cloudFormationSourceDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { file ->
                val templateText = BufferedReader(FileReader(file.toFile())).use { it.readText() }
                val generatedFile = templateText
                    .replace("\${codeS3Bucket}", codeBucket)
                    .replace("\${codeS3Key}", jarKey)
                val generatedFilePath = cloudFormationGeneratedDir.resolve(file.fileName)
                log.debug("Copying template from ${file.toAbsolutePath()} to ${generatedFilePath.toAbsolutePath()}")
                Files.write(generatedFilePath, generatedFile.toByteArray(Charsets.UTF_8))
            }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining the deployment goal; deploys an API and lambda function to AWS.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : OsirisMojo() {

    @Parameter
    private var staticFilesDirectory: String? = null

    override fun execute() {
        val jarName = jarFileName(project)
        val jarFile = Paths.get(project.build.directory).resolve(jarName)
        if (!Files.exists(jarFile)) throw MojoFailureException("Cannot find $jarName")
        val classLoader = URLClassLoader(arrayOf(jarFile.toUri().toURL()), javaClass.classLoader)
        val apiFactory = createApiFactory(classLoader)
        deploy(jarFile, apiFactory.api, apiFactory.config)
    }

    // TODO this logic should be pushed down into the AWS module. that will make Gradle support easier
    @Suppress("UNCHECKED_CAST")
    private fun deploy(jarFile: Path, api: Api<*>, appConfig: ApplicationConfig) {
        val credentialsProvider = DefaultAWSCredentialsProviderChain()
        val appName = appConfig.applicationName
        val region = appConfig.region
        val codeBucket = appConfig.codeBucket ?: createBucket(credentialsProvider, region, appName, "code")
        val (_, jarKey) = jarS3Key(project, appName)
        log.info("Uploading function code '$jarFile' to $codeBucket with key $jarKey")
        uploadFile(jarFile, codeBucket, region, credentialsProvider, jarKey)
        log.info("Upload of function code complete")
        uploadTemplates(codeBucket, credentialsProvider, appConfig.applicationName, appConfig.region)
        val deploymentTemplateUrl = if (Files.exists(rootTemplate)) {
            templateUrl(rootTemplate.fileName.toString(), codeBucket, region)
        } else {
            templateUrl(generatedTemplateName(appName), codeBucket, region)
        }
        val deployResult = deployStack(region, credentialsProvider, appName, deploymentTemplateUrl)
        val staticBucket = appConfig.staticFilesBucket ?: staticFilesBucketName(appConfig.applicationName)
        uploadStaticFiles(api, appConfig.region, credentialsProvider, staticBucket)
        val apiId = deployResult.apiId
        val stackCreated = deployResult.stackCreated
        val deployedStages = deployStages(credentialsProvider, region, apiId, appName, appConfig.stages, stackCreated)
        for (stage in deployedStages) {
            log.info("Deployed to stage '$stage' at https://$apiId.execute-api.$region.amazonaws.com/$stage/")
        }
    }

    private fun uploadStaticFiles(
        api: Api<*>,
        region: String,
        credentialsProvider: AWSCredentialsProvider,
        bucket: String
    ) {

        if (api.staticFiles) {
            val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDirectory.resolve("static")
            Files.walk(staticFilesDir, Int.MAX_VALUE)
                .filter { !Files.isDirectory(it) }
                .forEach { uploadFile(it, bucket, region, credentialsProvider, staticFilesDir) }
        }
    }

    private fun uploadTemplates(
        codeBucket: String,
        credentialsProvider: AWSCredentialsProvider,
        applicationName: String,
        region: String
    ) {

        if (!Files.exists(cloudFormationGeneratedDir)) return
        Files.list(cloudFormationGeneratedDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { templateFile ->
                val templateUrl = uploadFile(templateFile, codeBucket, region, credentialsProvider)
                log.debug("Uploaded template file ${templateFile.toAbsolutePath()}, S3 URL: $templateUrl")
            }
        val generatedTemplate = generatedTemplatePath(applicationName)
        val templateUrl = uploadFile(generatedTemplate, codeBucket, region, credentialsProvider)
        log.debug("Uploaded generated template file ${generatedTemplate.toAbsolutePath()}, S3 URL: $templateUrl")
    }
}

//--------------------------------------------------------------------------------------------------

private fun generatedTemplateParameters(rootTemplatePath: Path, codeBucketName: String, apiName: String): Set<String> {
    val templateBytes = Files.readAllBytes(rootTemplatePath)
    val templateYaml = String(templateBytes, Charsets.UTF_8)
    return generatedTemplateParameters(templateYaml, codeBucketName, apiName)
}

/**
 * Parses `root.template` and returns a set of all parameter names passed to the generated CloudFormation template.
 *
 * These are passed to the lambda as environment variables. This allows the handler code to refer to any
 * AWS resources defined in `root.template`.
 *
 * This allows (for example) for lambda functions to be defined in the project, created in `root.template`
 * and referenced in the project via environment variables.
 */
@Suppress("UNCHECKED_CAST")
internal fun generatedTemplateParameters(templateYaml: String, codeBucketName: String, apiName: String): Set<String> {
    val objectMapper = ObjectMapper(YAMLFactory())
    val rootTemplateMap = objectMapper.readValue(templateYaml, Map::class.java)
    val generatedTemplateUrl = "https://s3-\${AWS::Region}.amazonaws.com/$codeBucketName/$apiName.template"
    val parameters = (rootTemplateMap["Resources"] as Map<String, Any>?)
        ?.map { it.value as Map<String, Any> }
        ?.filter { it["Type"] == "AWS::CloudFormation::Stack" }
        ?.map { it["Properties"] as Map<String, Any> }
        ?.filter { it["TemplateURL"] == generatedTemplateUrl }
        ?.map { it["Parameters"] as Map<String, String> }
        ?.map { it.keys }
        ?.singleOrNull() ?: setOf()
    // The LambdaRole parameter is used by Osiris and doesn't need to be passed to the user code
    return parameters - "LambdaRole"
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
