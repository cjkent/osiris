package ws.osiris.awsdeploy

import org.slf4j.LoggerFactory
import ws.osiris.aws.ApiFactory
import ws.osiris.awsdeploy.cloudformation.deployStack
import ws.osiris.awsdeploy.cloudformation.writeTemplate
import ws.osiris.core.Api
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.stream.Collectors

private val log = LoggerFactory.getLogger("ws.osiris.awsdeploy")

/**
 * Implemented for each build system to hook into the project configuration.
 *
 * The configuration allows the code and CloudFormation template to be generated and the project to be deployed.
 */
interface DeployableProject {

    /** The project name; must be specified by the user in the Maven or Gradle project. */
    val name: String

    /** The project version; Maven requires a version but it's optional in Gradle. */
    val version: String?

    /** The root of the build directory. */
    val buildDir: Path

    /** The directory where jar files are built. */
    val jarBuildDir: Path

    /** The root of the main source directory; normally `src/main`. */
    val sourceDir: Path

    /** The root package of the application; used when generating the CloudFormation template. */
    val rootPackage: String

    /** The name of the environment into which the code is being deployed; used in resource and bucket names. */
    val environmentName: String?

    /** The directory containing the static files; null if the API doesn't serve static files. */
    val staticFilesDirectory: String?

    /** The name of the AWS profile; if not specified the default chain is used to find the profile and region. */
    val awsProfile: String?

    /**
     * Prefix prepended to the bucket names to ensure they are unique; bucket names must be unique across all
     * accounts in a region.
     *
     * If this is specified the bucket names will be something like
     *
     *     my-prefix.my-app.static-files
     *
     * If no prefix is specified the names will follow the pattern:
     *
     *     my-app.static-files
     */
    val bucketPrefix: String?

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
    private val profile: AwsProfile get() = awsProfile?.let { AwsProfile.named(it) } ?: AwsProfile.default()


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
        val codeBucket = appConfig.codeBucket
            ?: codeBucketName(appConfig.applicationName, environmentName, appConfig.bucketPrefix)
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
                environmentName,
                appConfig.bucketPrefix,
                appConfig.binaryMimeTypes
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
                    .replace("\${environmentName}", environmentName ?: "null")
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
        val appName = appConfig.applicationName
        val codeBucket = appConfig.codeBucket ?: createBucket(profile, appName, environmentName, "code", appConfig.bucketPrefix)
        val (_, jarKey) = jarS3Key(appName)
        log.info("Uploading function code '$jarFile' to $codeBucket with key $jarKey")
        uploadFile(profile, jarFile, codeBucket, jarKey)
        log.info("Upload of function code complete")
        uploadTemplates(codeBucket, appConfig.applicationName)
        val deploymentTemplateUrl = if (Files.exists(rootTemplate)) {
            templateUrl(rootTemplate.fileName.toString(), codeBucket, profile.region)
        } else {
            templateUrl(generatedTemplateName(appName), codeBucket, profile.region)
        }
        val apiEnvSuffix = if (environmentName == null) "" else ".$environmentName"
        val apiName = "${appConfig.applicationName}$apiEnvSuffix"
        val stackEnvSuffix = if (environmentName == null) "" else "-$environmentName"
        val stackName = "${appConfig.applicationName}$stackEnvSuffix"
        val deployResult = deployStack(profile, stackName, apiName, deploymentTemplateUrl)
        val staticBucket = appConfig.staticFilesBucket
            ?: staticFilesBucketName(appConfig.applicationName, environmentName, appConfig.bucketPrefix)
        uploadStaticFiles(api, staticBucket, staticFilesDirectory)
        val apiId = deployResult.apiId
        val stackCreated = deployResult.stackCreated
        val deployedStages = deployStages(profile, apiId, apiName, appConfig.stages, stackCreated)
        val stageUrls = deployedStages.associate { Pair(it, "https://$apiId.execute-api.${profile.region}.amazonaws.com/$it/") }
        for ((stage, url) in stageUrls) {
            log.info("Deployed to stage '$stage' at $url")
        }
        return stageUrls
    }

    private fun uploadStaticFiles(api: Api<*>, bucket: String, staticFilesDirectory: String?) {
        val staticFilesInfo = staticFilesInfo(api, staticFilesDirectory) ?: return
        val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDir.resolve("static")
        for (file in staticFilesInfo.files) {
            uploadFile(profile, file, bucket, staticFilesDir, bucketDir = staticFilesInfo.hash)
        }
    }

    private fun uploadTemplates(codeBucket: String, appName: String) {
        if (!Files.exists(cloudFormationGeneratedDir)) return
        Files.list(cloudFormationGeneratedDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { templateFile ->
                val templateUrl = uploadFile(profile, templateFile, codeBucket)
                log.debug("Uploaded template file ${templateFile.toAbsolutePath()}, S3 URL: $templateUrl")
            }
        val generatedTemplate = generatedTemplatePath(appName)
        val templateUrl = uploadFile(profile, generatedTemplate, codeBucket)
        log.debug("Uploaded generated template file ${generatedTemplate.toAbsolutePath()}, S3 URL: $templateUrl")
    }

    private fun staticFilesInfo(api: Api<*>, staticFilesDirectory: String?): StaticFilesInfo? {
        if (!api.staticFiles) {
            return null
        }
        val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDir.resolve("static")
        val staticFiles = Files.walk(staticFilesDir, Int.MAX_VALUE)
            .filter { !Files.isDirectory(it) }
            .collect(Collectors.toList())
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
