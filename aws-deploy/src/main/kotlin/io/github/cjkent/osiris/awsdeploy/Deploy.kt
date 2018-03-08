package io.github.cjkent.osiris.awsdeploy

import com.amazonaws.regions.DefaultAwsRegionProviderChain
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.apigateway.model.CreateDeploymentRequest
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.github.cjkent.osiris.aws.ApiFactory
import io.github.cjkent.osiris.aws.Stage
import io.github.cjkent.osiris.awsdeploy.cloudformation.deployStack
import io.github.cjkent.osiris.awsdeploy.cloudformation.writeTemplate
import io.github.cjkent.osiris.core.Api
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.stream.Collectors.toList

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.aws")

/**
 * Deploys the API to the stages and returns the names of the stages that were updated.
 *
 * If the API is being deployed for the first time then all stages are deployed. If the API
 * was updated then only stages where `deployOnUpdate` is true are deployed.
 */
fun deployStages(apiId: String, apiName: String, stages: List<Stage>, stackCreated: Boolean): List<String> {
    // no need to deploy stages if the stack has just been created
    return if (stackCreated) {
        stages.map { it.name }
    } else {
        val apiGateway = AmazonApiGatewayClientBuilder.defaultClient()
        val stagesToDeploy = stages.filter { it.deployOnUpdate }
        for (stage in stagesToDeploy) {
            log.debug("Updating REST API '$apiName' in stage '${stage.name}'")
            apiGateway.createDeployment(CreateDeploymentRequest().apply {
                restApiId = apiId
                stageName = stage.name
                variables = stage.variables
                description = stage.description
            })
        }
        stagesToDeploy.map { it.name }
    }
}

/**
 * Creates an S3 bucket to hold static files.
 *
 * The bucket name is `${API name}.static-files`, converted to lower case.
 *
 * If the bucket already exists the function does nothing.
 */
fun createBucket(apiName: String, envName: String?, suffix: String): String {
    val s3Client = AmazonS3ClientBuilder.defaultClient()
    val bucketName = bucketName(apiName, envName, suffix)
    if (!s3Client.doesBucketExistV2(bucketName)) {
        s3Client.createBucket(bucketName)
        log.info("Created S3 bucket '$bucketName'")
    } else {
        log.info("Using existing S3 bucket '$bucketName'")
    }
    return bucketName
}

/**
 * Uploads a file to an S3 bucket and returns the URL of the file in S3.
 */
fun uploadFile(file: Path, bucketName: String, key: String? = null): String =
    uploadFile(file, bucketName, file.parent, key)

/**
 * Uploads a file to an S3 bucket and returns the URL of the file in S3.
 *
 * The file should be under `baseDir` on the filesystem. The S3 key for the file will be the relative path
 * from the base directory to the file.
 *
 * For example, if `baseDir` is `/foo/bar` and the file is `/foo/bar/baz/qux.txt` then the file will be
 * uploaded to S3 with the key `baz/qux.txt
 *
 * The key can be specified by the caller in which case it is used instead of automatically generating
 * a key.
 */
fun uploadFile(file: Path, bucketName: String, baseDir: Path, key: String? = null, bucketDir: String? = null): String {
    val s3Client = AmazonS3ClientBuilder.defaultClient()
    val region = DefaultAwsRegionProviderChain().region
    val uploadKey = key ?: baseDir.relativize(file).toString()
    val dirPart = bucketDir?.let { "$bucketDir/" } ?: ""
    val fullKey = "$dirPart$uploadKey"
    s3Client.putObject(bucketName, fullKey, file.toFile())
    val url = "https://s3-$region.amazonaws.com/$bucketName/$fullKey"
    log.debug("Uploaded file {} to S3 bucket {}, URL {}", file, bucketName, url)
    return url
}

/**
 * Returns the name of a bucket for the group and API with the specified suffix.
 *
 * The bucket name is `<API name>.<suffix>`
 */
fun bucketName(apiName: String, envName: String?, suffix: String): String {
    val accountPart = if (envName == null) "" else "$envName."
    return "$apiName.$accountPart$suffix"
}

/**
 * Returns the default name of the S3 bucket from which code is deployed
 */
fun codeBucketName(apiName: String, envName: String?): String = bucketName(apiName, envName, "code")

/**
 * Returns the name of the static files bucket for the API.
 */
fun staticFilesBucketName(apiName: String, envName: String?): String = bucketName(apiName,
    envName,
    "static-files")

/**
 * Equivalent of Maven's `MojoFailureException` - indicates something has failed during the deployment.
 */
class DeployException(msg: String) : RuntimeException(msg)

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

/**
 * Implemented for each build system to hook into the project configuration.
 *
 * The configuration allows the code and CloudFormation template to be generated and the project to be deployed.
 */
interface DeployableProject {

    // These must be specified by the user in the Maven or Gradle project
    val name: String

    // Maven requires a version but it's optional in Gradle
    val version: String?

    // These must be provided by the Maven or Gradle project
    /** The root of the build directory. */
    val buildDir: Path
    /** The directory where jar files are built. */
    val jarBuildDir: Path
    val sourceDir: Path

    // This must come from the Maven or Gradle project, but can be defaulted (in Maven at least)
    val rootPackage: String

    val environmentName: String?

    /** The directory containing the static files; null if the API doesn't serve static files. */
    val staticFilesDirectory: String?

    private val cloudFormationSourceDir: Path get() = sourceDir.resolve("cloudformation")
    private val rootTemplate: Path get() = cloudFormationSourceDir.resolve("root.template")
    private val generatedCorePackage: String get() = "$rootPackage.core.generated"
    private val lambdaClassName: String get() = "$generatedCorePackage.GeneratedLambda"
    private val lambdaHandler: String get() = "$lambdaClassName::handle"
    private val cloudFormationGeneratedDir: Path get() = buildDir.resolve("cloudformation")
    private val apiFactoryClassName: String get() = "$generatedCorePackage.GeneratedApiFactory"
    private val jarFile: Path get() = jarBuildDir.resolve(jarName)
    private val jarName: String get() = if (version == null) {
        "$name-jar-with-dependencies.jar"
    } else {
        "$name-$version-jar-with-dependencies.jar"
    }

    /**
     * Returns a factory that can build the API, the components and the application configuration.
     */
    fun createApiFactory(parentClassLoader: ClassLoader): ApiFactory<*> {
        if (!Files.exists(jarFile)) throw DeployException("Cannot find ${jarFile.toAbsolutePath()}")
        val classLoader = URLClassLoader(arrayOf(jarFile.toUri().toURL()), parentClassLoader)
        val apiFactoryClass = Class.forName(apiFactoryClassName, true, classLoader)
        return apiFactoryClass.newInstance() as ApiFactory<*>
    }

    fun generateCloudFormation() {
        val apiFactory = createApiFactory(javaClass.classLoader)
        val api = apiFactory.api
        val appConfig = apiFactory.config
        val codeBucket = appConfig.codeBucket ?: codeBucketName(appConfig.applicationName, environmentName)
        val (codeHash, jarKey) = jarS3Key(appConfig.applicationName)
        val lambdaHandler = lambdaHandler
        val rootTemplateExists = Files.exists(rootTemplate)
        val templateParams = if (rootTemplateExists) {
            // Parse the parameters from root.template and pass them to the lambda as env vars
            // This allows the handler code to reference any resources defined in root.template
            generatedTemplateParameters(rootTemplate, codeBucket, appConfig.applicationName)
        } else {
            setOf()
        }
        val staticHash = staticFilesInfo(api, staticFilesDirectory)?.hash
        val createLambdaRole = !rootTemplateExists
        val generatedTemplatePath = generatedTemplatePath(appConfig.applicationName)
        Files.deleteIfExists(generatedTemplatePath)
        Files.createDirectories(generatedTemplatePath.parent)
        Files.newBufferedWriter(generatedTemplatePath, StandardOpenOption.CREATE).use {
            writeTemplate(
                it,
                api,
                appConfig,
                templateParams,
                lambdaHandler,
                codeHash,
                staticHash,
                codeBucket,
                jarKey,
                createLambdaRole,
                environmentName
            )
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
                    .replace("\${envName}", environmentName ?: "null")
                val generatedFilePath = cloudFormationGeneratedDir.resolve(file.fileName)
                log.debug("Copying template from ${file.toAbsolutePath()} to ${generatedFilePath.toAbsolutePath()}")
                Files.write(generatedFilePath, generatedFile.toByteArray(Charsets.UTF_8))
            }
    }

    private data class JarKey(val hash: String, val name: String)

    private fun jarS3Key(apiName: String): JarKey {
        val jarPath = jarBuildDir.resolve(jarName)
        val md5Hash = md5Hash(jarPath)
        return JarKey(md5Hash, "$apiName.$md5Hash.jar")
    }

    private fun generatedTemplateName(appName: String): String = "$appName.template"

    private fun md5Hash(vararg files: Path): String {
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
        for (file in files) {
            Files.newInputStream(file).buffered(1024 * 1024).use { readChunk(it) }
        }
        val digest = messageDigest.digest()
        return digest.joinToString("") { String.format("%02x", it) }
    }

    private fun templateUrl(templateName: String, codeBucket: String, region: String): String =
        "https://s3-$region.amazonaws.com/$codeBucket/$templateName"


    private fun generatedTemplateParameters(rootTemplatePath: Path,
        codeBucketName: String,
        apiName: String): Set<String> {
        val templateBytes = Files.readAllBytes(rootTemplatePath)
        val templateYaml = String(templateBytes, Charsets.UTF_8)
        return generatedTemplateParameters(templateYaml, codeBucketName, apiName)
    }

    fun generatedTemplatePath(appName: String): Path =
        cloudFormationGeneratedDir.resolve(generatedTemplateName(appName))

    @Suppress("UNCHECKED_CAST")
    fun deploy(): Map<String, String> {
        if (!Files.exists(jarFile)) throw DeployException("Cannot find $jarName")
        val classLoader = URLClassLoader(arrayOf(jarFile.toUri().toURL()), javaClass.classLoader)
        val apiFactory = createApiFactory(classLoader)
        val appConfig = apiFactory.config
        val api = apiFactory.api
        val region = DefaultAwsRegionProviderChain().region
        val appName = appConfig.applicationName
        val codeBucket = appConfig.codeBucket ?: createBucket(appName, environmentName, "code")
        val (_, jarKey) = jarS3Key(appName)
        log.info("Uploading function code '$jarFile' to $codeBucket with key $jarKey")
        uploadFile(jarFile, codeBucket, jarKey)
        log.info("Upload of function code complete")
        uploadTemplates(codeBucket, appConfig.applicationName)
        val deploymentTemplateUrl = if (Files.exists(rootTemplate)) {
            templateUrl(rootTemplate.fileName.toString(), codeBucket, region)
        } else {
            templateUrl(generatedTemplateName(appName), codeBucket, region)
        }
        val apiEnvSuffix = if (environmentName == null) "" else ".$environmentName"
        val apiName = "${appConfig.applicationName}$apiEnvSuffix"
        val stackEnvSuffix = if (environmentName == null) "" else "-$environmentName"
        val stackName = "${appConfig.applicationName}$stackEnvSuffix"
        val deployResult = deployStack(region, stackName, apiName, deploymentTemplateUrl)
        val staticBucket = appConfig.staticFilesBucket ?: staticFilesBucketName(appConfig.applicationName, environmentName)
        uploadStaticFiles(api, staticBucket, staticFilesDirectory)
        val apiId = deployResult.apiId
        val stackCreated = deployResult.stackCreated
        val deployedStages = deployStages(apiId, apiName, appConfig.stages, stackCreated)
        val stageUrls = deployedStages.associate { Pair(it, "https://$apiId.execute-api.$region.amazonaws.com/$it/") }
        for ((stage, url) in stageUrls) {
            log.info("Deployed to stage '$stage' at $url")
        }
        return stageUrls
    }

    private fun uploadStaticFiles(api: Api<*>, bucket: String, staticFilesDirectory: String?) {
        val staticFilesInfo = staticFilesInfo(api, staticFilesDirectory) ?: return
        val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDir.resolve("static")
        for (file in staticFilesInfo.files) {
            uploadFile(file, bucket, staticFilesDir, bucketDir = staticFilesInfo.hash)
        }
    }

    private fun uploadTemplates(codeBucket: String, appName: String) {
        if (!Files.exists(cloudFormationGeneratedDir)) return
        Files.list(cloudFormationGeneratedDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { templateFile ->
                val templateUrl = uploadFile(templateFile, codeBucket)
                log.debug("Uploaded template file ${templateFile.toAbsolutePath()}, S3 URL: $templateUrl")
            }
        val generatedTemplate = generatedTemplatePath(appName)
        val templateUrl = uploadFile(generatedTemplate, codeBucket)
        log.debug("Uploaded generated template file ${generatedTemplate.toAbsolutePath()}, S3 URL: $templateUrl")
    }

    private fun staticFilesInfo(api: Api<*>, staticFilesDirectory: String?): StaticFilesInfo? {
        if (!api.staticFiles) {
            return null
        }
        val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDir.resolve("static")
        val staticFiles = Files.walk(staticFilesDir, Int.MAX_VALUE)
            .filter { !Files.isDirectory(it) }
            .collect(toList())
        val hash = md5Hash(*staticFiles.toTypedArray())
        return StaticFilesInfo(staticFiles, hash)
    }
}

/**
 * The static files and the hash of all of them together.
 *
 * The hash is used to derive the name of the folder in the static files bucket that the files are deployed to.
 * Each different set of files must be uploaded to a different location to that different stages can use
 * different sets of files. Using the hash to name a subdirectory of the static files bucket has two advantages:
 *
 * * The template generation code and deployment code can both derive the same location
 * * A new set of files is only created when any of them change and the hash changes
 */
private class StaticFilesInfo(val files: List<Path>, val hash: String)
