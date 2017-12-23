package io.github.cjkent.osiris.awsdeploy

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.apigateway.model.CreateDeploymentRequest
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.aws")

/**
 * Deploys the API to the stages and returns the names of the stages that were updated.
 *
 * If the API is being deployed for the first time then all stages are deployed. If the API
 * was updated then only stages where `deployOnUpdate` is true are deployed.
 */
fun deployStages(
    credentialsProvider: AWSCredentialsProvider,
    region: String,
    apiId: String,
    apiName: String,
    stages: List<Stage>,
    stackCreated: Boolean
): List<String> {

    // no need to deploy stages if the stack has just been created
    return if (stackCreated) {
        stages.map { it.name }
    } else {
        val apiGateway = AmazonApiGatewayClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region)
            .build()
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
fun createBucket(credentialsProvider: AWSCredentialsProvider, region: String, apiName: String, suffix: String): String {
    val s3Client = AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region).build()
    val bucketName = bucketName(apiName, suffix)
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
fun uploadFile(
    file: Path,
    bucketName: String,
    region: String,
    credentialsProvider: AWSCredentialsProvider,
    key: String? = null
): String = uploadFile(
    file,
    bucketName,
    region,
    credentialsProvider,
    file.parent,
    key
)

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
    file: Path,
    bucketName: String,
    region: String,
    credentialsProvider: AWSCredentialsProvider,
    baseDir: Path,
    key: String? = null
): String {

    val s3Client = AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region).build()
    val uploadKey = key ?: baseDir.relativize(file).toString()
    s3Client.putObject(bucketName, uploadKey, file.toFile())
    log.debug("Uploaded file {} to S3 bucket {}", file, bucketName)
    return "https://s3-$region.amazonaws.com/$bucketName/$uploadKey"
}

data class Stage(
    val name: String,
    val variables: Map<String, String>,
    val deployOnUpdate: Boolean,
    val description: String
)

/**
 * Returns the name of a bucket for the group and API with the specified suffix.
 *
 * The bucket name is `<API name>.<suffix>`
 */
fun bucketName(apiName: String, suffix: String) = "$apiName.$suffix"

/**
 * Returns the default name of the S3 bucket from which code is deployed
 */
fun codeBucketName(apiName: String): String = bucketName(apiName, "code")

/**
 * Returns the name of the static files bucket for the API.
 */
fun staticFilesBucketName(apiName: String): String = bucketName(apiName, "static-files")
