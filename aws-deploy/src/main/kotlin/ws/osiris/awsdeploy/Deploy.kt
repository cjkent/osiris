package ws.osiris.awsdeploy

import com.amazonaws.services.apigateway.model.CreateDeploymentRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.slf4j.LoggerFactory
import ws.osiris.aws.Stage
import ws.osiris.aws.validateBucketName
import java.nio.file.Path

private val log = LoggerFactory.getLogger("ws.osiris.awsdeploy")

/**
 * Deploys the API to the stages and returns the names of the stages that were updated.
 *
 * If the API is being deployed for the first time then all stages are deployed. If the API
 * was updated then only stages where `deployOnUpdate` is true are deployed.
 */
fun deployStages(
    profile: AwsProfile,
    apiId: String,
    apiName: String,
    stages: List<Stage>,
    stackCreated: Boolean
): List<String> {
    // no need to deploy stages if the stack has just been created
    return if (stackCreated) {
        stages.map { it.name }
    } else {
        val stagesToDeploy = stages.filter { it.deployOnUpdate }
        for (stage in stagesToDeploy) {
            log.debug("Updating REST API '$apiName' in stage '${stage.name}'")
            profile.apiGatewayClient.createDeployment(CreateDeploymentRequest().apply {
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

/**
 * Creates an S3 bucket.
 *
 * If the bucket already exists the function does nothing.
 */
fun createBucket(profile: AwsProfile, bucketName: String): String {
    validateBucketName(bucketName)
    if (!profile.s3Client.doesBucketExistV2(bucketName)) {
        profile.s3Client.createBucket(bucketName)
        log.info("Created S3 bucket '$bucketName'")
    } else {
        log.info("Using existing S3 bucket '$bucketName'")
    }
    return bucketName
}

/**
 * Uploads a file to an S3 bucket and returns the URL of the file in S3.
 */
fun uploadFile(profile: AwsProfile, file: Path, bucketName: String, key: String? = null): String =
    uploadFile(profile, file, bucketName, file.parent, key)

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
fun uploadFile(
    profile: AwsProfile,
    file: Path,
    bucketName: String,
    baseDir: Path,
    key: String? = null,
    bucketDir: String? = null
): String {
    val uploadKey = key ?: baseDir.relativize(file).toString()
    val dirPart = bucketDir?.let { "$bucketDir/" } ?: ""
    val fullKey = "$dirPart$uploadKey"
    profile.s3Client.putObject(bucketName, fullKey, file.toFile())
    val url = "https://$bucketName.s3.amazonaws.com/$fullKey"
    log.debug("Uploaded file {} to S3 bucket {}, URL {}", file, bucketName, url)
    return url
}

/**
 * Returns the name of a bucket for the environment and API with the specified prefix and suffix.
 *
 * The bucket name is `${prefix}-${apiName}-${envName}-${suffix}`.
 *
 * If the [envName] or [prefix] are `null` then the corresponding dashes aren't included.
 */
fun bucketName(appName: String, envName: String?, suffix: String, prefix: String?): String {
    val envPart = if (envName == null) "" else "${envName.toLowerCase()}-"
    val prefixPart = if (prefix == null) "" else "${prefix.toLowerCase()}-"
    val appNamePart = appName.toLowerCase()
    // TODO validate name against pattern in Api.kt
    return "$prefixPart$appNamePart-$envPart$suffix"
}

/**
 * Returns the default name of the S3 bucket from which code is deployed
 */
fun codeBucketName(apiName: String, envName: String?, prefix: String?): String =
    bucketName(apiName, envName, "code", prefix)

/**
 * Returns the name of the static files bucket for the API.
 */
fun staticFilesBucketName(apiName: String, envName: String?, prefix: String?): String =
    bucketName(apiName, envName, "staticfiles", prefix)

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
internal fun generatedTemplateParameters(templateYaml: String, apiName: String): Set<String> {
    val objectMapper = ObjectMapper(YAMLFactory())
    val rootTemplateMap = objectMapper.readValue(templateYaml, Map::class.java)
    val parameters = (rootTemplateMap["Resources"] as Map<String, Any>?)
        ?.map { it.value as Map<String, Any> }
        ?.filter { it["Type"] == "AWS::CloudFormation::Stack" }
        ?.map { it["Properties"] as Map<String, Any> }
        ?.filter { (it["TemplateURL"] as? String)?.endsWith("/$apiName.template") ?: false }
        ?.map { it["Parameters"] as Map<String, String> }
        ?.map { it.keys }
        ?.singleOrNull() ?: setOf()
    // These parameters are used by Osiris and don't need to be passed to the user code
    return parameters - "LambdaRole" - "CustomAuthArn"
}

