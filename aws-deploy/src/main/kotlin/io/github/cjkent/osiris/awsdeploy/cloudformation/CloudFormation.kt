package io.github.cjkent.osiris.awsdeploy.cloudformation

import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.apigateway.model.GetRestApisRequest
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.cloudformation.model.CreateStackRequest
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.model.StackStatus
import com.amazonaws.services.cloudformation.model.UpdateStackRequest
import io.github.cjkent.osiris.aws.ApplicationConfig
import io.github.cjkent.osiris.aws.CognitoUserPoolsAuth
import io.github.cjkent.osiris.aws.CustomAuth
import io.github.cjkent.osiris.awsdeploy.staticFilesBucketName
import io.github.cjkent.osiris.core.Api
import org.slf4j.LoggerFactory
import java.io.Writer

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.aws.cloudformation")

private const val CAPABILITY_IAM = "CAPABILITY_IAM"

/** If a stack has any of these statuses it must be updated. */
private val updateStatuses = setOf(
    StackStatus.CREATE_COMPLETE,
    StackStatus.UPDATE_COMPLETE,
    StackStatus.UPDATE_COMPLETE_CLEANUP_IN_PROGRESS,
    StackStatus.UPDATE_ROLLBACK_COMPLETE,
    StackStatus.UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS
)

/** If a stack has any of these statuses it must be created. */
private val createStatuses = setOf(
    StackStatus.CREATE_FAILED,
    StackStatus.DELETE_COMPLETE
)

/** If a stack has a status in this set it must be deleted and recreated. */
private val deleteStatuses = setOf(
    StackStatus.ROLLBACK_COMPLETE
)

/** The statuses indicating a successful deployment. */
private val deployedStatuses = setOf(
    StackStatus.CREATE_COMPLETE,
    StackStatus.UPDATE_COMPLETE
)

/** If a stack has any of these statuses it indicates the operation succeeded. */
private val successStatuses = setOf(
    StackStatus.CREATE_COMPLETE,
    StackStatus.DELETE_COMPLETE,
    StackStatus.UPDATE_COMPLETE
)

/** If a stack has any of these statuses it means the operation failed. */
private val failedStatuses = setOf(
    StackStatus.CREATE_FAILED,
    StackStatus.ROLLBACK_IN_PROGRESS,
    StackStatus.ROLLBACK_FAILED,
    StackStatus.DELETE_FAILED,
    StackStatus.ROLLBACK_COMPLETE,
    StackStatus.UPDATE_ROLLBACK_IN_PROGRESS,
    StackStatus.UPDATE_ROLLBACK_FAILED,
    StackStatus.UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS,
    StackStatus.UPDATE_ROLLBACK_COMPLETE
)

// TODO parameter class for code hash / bucket / key?
/**
 * Writes a CloudFormation template for all the resources needed for the API:
 *
 * * API Gateway resources, methods and integrations for endpoints handled by the lambda
 * * API Gateway resources, methods, integrations, method responses and integration responses for
 *   endpoints serving static files from S3
 * * The lambda function
 * * The role used by the lambda function (unless an existing role is provided)
 * * The permissions for the role (unless an existing role is provided)
 * * The stages
 * * A deployment (if any stages are defined)
 * * The S3 bucket from which static files are served (unless an existing bucket is provided)
 */
fun writeTemplate(
    writer: Writer,
    api: Api<*>,
    appConfig: ApplicationConfig,
    templateParams: Set<String>,
    lambdaHandler: String,
    codeHash: String,
    staticHash: String?,
    codeBucket: String,
    codeKey: String,
    createLambdaRole: Boolean,
    envName: String?
) {

    val authTypes = api.routes.map { it.auth }.toSet()
    val cognitoAuth = if (authTypes.contains(CognitoUserPoolsAuth)) {
        log.debug("Found endpoints with Cognito User Pools auth")
        true
    } else {
        false
    }
    val customAuth = if (authTypes.contains(CustomAuth)) {
        log.debug("Found endpoints with custom auth")
        true
    } else {
        false
    }
    val authConfig = appConfig.authConfig
    // If the authConfig is provided it means the custom auth lambda or cognito user pool is defined outside this
    // stack and its ARN is provided. which means there is no need for a template parameter to pass in the ARN.
    // the ARN is known and can be directly included in the template.
    // however, if custom auth or cognito auth is not used, it means the custom auth lambda or cognito user pool
    // is defined in the stack and must be passed into the generated template
    val cognitoAuthParam = if (cognitoAuth && appConfig.authConfig == null) {
        log.debug("Found endpoints with Cognito auth but no external auth config. " +
            "Will create template parameter CognitoUserPoolArn. " +
            "User pool must be defined in root.template")
        true
    } else {
        false
    }
    val customAuthParam = if (customAuth && appConfig.authConfig == null) {
        log.debug("Found endpoints with custom auth but no external auth config. " +
            "Will create template parameter CustomAuthArn. " +
            "Custom auth lambda must be defined in root.template")
        true
    } else {
        false
    }
    ParametersTemplate(!createLambdaRole, cognitoAuthParam, customAuthParam, templateParams).write(writer)
    writer.write("Resources:")
    val staticFilesBucket = if (api.staticFiles) {
        appConfig.staticFilesBucket ?: writeStaticFilesBucketTemplate(writer, appConfig.applicationName, envName)
    } else {
        "not used" // TODO this smells bad - make it nullable all the way down?
    }
    val apiTemplate = ApiTemplate.create(
        api,
        appConfig.applicationName,
        appConfig.applicationDescription,
        envName,
        staticFilesBucket,
        staticHash
    )
    val lambdaTemplate = LambdaTemplate(
        lambdaHandler,
        appConfig.lambdaMemorySizeMb,
        appConfig.lambdaTimeout.seconds.toInt(),
        codeBucket,
        codeKey,
        appConfig.environmentVariables,
        templateParams,
        envName,
        createLambdaRole
    )
    val publishLambdaTemplate = PublishLambdaTemplate(codeHash)
    apiTemplate.write(writer)
    if (customAuth) {
        CustomAuthorizerTemplate(authConfig).write(writer)
    } else if (cognitoAuth) {
        CognitoAuthorizerTemplate(authConfig).write(writer)
    }
    lambdaTemplate.write(writer)
    publishLambdaTemplate.write(writer)
    if (api.staticFiles) {
        StaticFilesRoleTemplate("arn:aws:s3:::$staticFilesBucket").write(writer)
    }
    if (createLambdaRole) {
        LambdaRoleTemplate().write(writer)
    }
    if (!appConfig.stages.isEmpty()) {
        DeploymentTemplate(apiTemplate).write(writer)
        appConfig.stages.forEach { StageTemplate(it).write(writer) }
    }
    val authorizer = cognitoAuth || customAuth
    OutputsTemplate(codeBucket, codeKey, authorizer).write(writer)
}

/**
 * Adds an S3 bucket for the static files to the template.
 *
 * @return the bucket name
 */
private fun writeStaticFilesBucketTemplate(writer: Writer, apiName: String, envName: String?): String {
    val bucketName = staticFilesBucketName(apiName, envName)
    val bucketTemplate = S3BucketTemplate(bucketName)
    bucketTemplate.write(writer)
    return bucketName
}

/**
 * Information about an API that has been deployed.
 */
class DeployResult(
    /** true if a new stack was created, false if an existing stack was updated. */
    val stackCreated: Boolean,
    /** The ID of the API that was updated or created. */
    val apiId: String
)

/**
 * Deploys the CloudformationStack and returns the ID of the API Gateway API.
 */
fun deployStack(region: String, stackName: String, apiName: String, templateUrl: String): DeployResult {
    log.debug("Deploying stack to region {} using template {}", region, templateUrl)
    val cloudFormationClient = AmazonCloudFormationClientBuilder.defaultClient()
    val stackSummaries = cloudFormationClient.listStacks().stackSummaries
    val liveStacks = stackSummaries.filter { it.stackName == stackName && it.stackStatus != "DELETE_COMPLETE" }
    val (stackId, created) = if (liveStacks.isEmpty()) {
        val stackId = createStack(stackName, cloudFormationClient, templateUrl); Pair(stackId, true)
    } else if (liveStacks.size > 1) {
        throw IllegalStateException("Found multiple stacks named '$stackName': $liveStacks")
    } else {
        val stackSummary = liveStacks[0]
        val status = StackStatus.fromValue(stackSummary.stackStatus)
        if (deleteStatuses.contains(status)) {
            deleteStack(stackName, cloudFormationClient)
            val stackId = createStack(stackName, cloudFormationClient, templateUrl)
            Pair(stackId, true)
        } else if (updateStatuses.contains(status)) {
            val stackId = updateStack(stackName, cloudFormationClient, templateUrl)
            Pair(stackId, false)
        } else if (createStatuses.contains(status)) {
            val stackId = createStack(stackName, cloudFormationClient, templateUrl)
            Pair(stackId, true)
        } else {
            throw IllegalStateException("Unable to deploy stack '$stackName' with status ${stackSummary.stackStatus}")
        }
    }
    val describeResult = cloudFormationClient.describeStacks(DescribeStacksRequest().apply { this.stackName = stackId })
    if (describeResult.stacks.size != 1) throw IllegalStateException("Multiple stacks found: ${describeResult.stacks}")
    val stack = describeResult.stacks[0]
    val status = StackStatus.fromValue(stack.stackStatus)
    if (!deployedStatuses.contains(status)) throw IllegalStateException("Stack status is ${stack.stackStatus}")
    return DeployResult(created, apiId(apiName))
}

//--------------------------------------------------------------------------------------------------

private fun apiId(apiName: String): String {
    val apiGatewayClient = AmazonApiGatewayClientBuilder.defaultClient()
    return apiGatewayClient.getRestApis(GetRestApisRequest()).items.find { it.name == apiName }?.id
        ?: throw IllegalStateException("No API found with name '$apiName'")
}

private fun deleteStack(apiName: String, cloudFormationClient: AmazonCloudFormation) {
    log.info("Deleting stack '$apiName'")
    val describeResult = cloudFormationClient.describeStacks(DescribeStacksRequest().apply { stackName = apiName })
    val stackId = describeResult.stacks[0].stackId
    cloudFormationClient.deleteStack(DeleteStackRequest().apply { stackName = apiName })
    waitForStack(stackId, cloudFormationClient)
    log.info("Deleted stack '$apiName'")
}

private fun updateStack(apiName: String, cloudFormationClient: AmazonCloudFormation, templateUrl: String): String? {
    log.info("Updating stack '{}'", apiName)
    val updateResult = cloudFormationClient.updateStack(UpdateStackRequest().apply {
        stackName = apiName
        templateURL = templateUrl
        setCapabilities(listOf(CAPABILITY_IAM))
    })
    waitForStack(updateResult.stackId, cloudFormationClient)
    log.info("Stack updated. ID = ${updateResult.stackId}")
    return updateResult.stackId
}

private fun createStack(apiName: String, cloudFormationClient: AmazonCloudFormation, templateUrl: String): String? {
    log.info("Creating stack '{}'", apiName)
    val createResult = cloudFormationClient.createStack(CreateStackRequest().apply {
        stackName = apiName
        templateURL = templateUrl
        setCapabilities(listOf(CAPABILITY_IAM))
    })
    waitForStack(createResult.stackId, cloudFormationClient)
    log.info("Stack created. ID = ${createResult.stackId}")
    return createResult.stackId
}

// TODO can this be done using waiters?
/**
 * Waits for a stack to finish deploying; returns when the stack status indicates success (`CREATE_COMPLETE` or
 * `UPDATE_COMPLETE` or `DELETE_COMPLETE`). Throws an exception if the state indicates that deployment
 * has failed.
 *
 * The looping and polling is necessary because the AWS APIs don't provide any efficient way to find out
 * when an operation is actually complete. The so-called synchronous API returns as soon as the operation
 * has started. This means that the stack is normally still being created or updated when the call returns
 * so there's no way to know whether deployment was successful.
 */
private fun waitForStack(stackId: String, cloudFormationClient: AmazonCloudFormation) {
    tailrec fun waitForStack(count: Int) {
        val stackSummary = cloudFormationClient.listStacks().stackSummaries.filter { it.stackId == stackId }[0]
        val status = StackStatus.fromValue(stackSummary.stackStatus)
        if (successStatuses.contains(status)) {
            log.debug("Stack status $status, returning")
            return
        } else if (failedStatuses.contains(status)) {
            throw IllegalStateException("Deployment failed, stack status: $status")
        } else {
            log.debug("Stack status $status, waiting")
            if (count % 5 == 0) log.info("Waiting for stack to deploy...")
            Thread.sleep(1000)
            return waitForStack(count + 1)
        }
    }
    return waitForStack(1)
}
