package ws.osiris.awsdeploy.cloudformation

import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.apigateway.model.GetRestApisRequest
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.cloudformation.model.CreateStackRequest
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStackResourceRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.model.StackStatus
import com.amazonaws.services.cloudformation.model.UpdateStackRequest
import org.slf4j.LoggerFactory
import ws.osiris.awsdeploy.AwsProfile

private val log = LoggerFactory.getLogger("ws.osiris.aws.cloudformation")

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
 * Information about an API that has been deployed.
 */
class DeployResult(
    /** true if a new stack was created, false if an existing stack was updated. */
    val stackCreated: Boolean,
    /** The ID of the API that was updated or created. */
    val apiId: String,
    /** The ARN of the lambda function version. */
    val lambdaVersionArn: String,
    /** The ARN of the lambda function that sends keep-alive messages; null if keep-alive is disabled. */
    val keepAliveLambdaArn: String?
)

/**
 * Deploys the CloudformationStack and returns the ID of the API Gateway API.
 */
fun deployStack(profile: AwsProfile, stackName: String, apiName: String, templateUrl: String): DeployResult {
    log.debug("Deploying stack to region {} using template {}", profile.region, templateUrl)
    val cloudFormationClient = AmazonCloudFormationClientBuilder.standard()
        .withCredentials(profile.credentialsProvider)
        .withRegion(profile.region)
        .build()
    val stackSummaries = cloudFormationClient.listStacks().stackSummaries
    val liveStacks = stackSummaries.filter { it.stackName == stackName && it.stackStatus != "DELETE_COMPLETE" }
    val (_, created) = if (liveStacks.isEmpty()) {
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
    val apiStackResourceResult = cloudFormationClient.describeStackResource(DescribeStackResourceRequest().apply {
        this.stackName = stackName
        this.logicalResourceId = "ApiStack"
    })
    val describeResult = cloudFormationClient.describeStacks(DescribeStacksRequest().apply {
        this.stackName = apiStackResourceResult.stackResourceDetail.physicalResourceId
    })
    if (describeResult.stacks.size != 1) throw IllegalStateException("Multiple stacks found: ${describeResult.stacks}")
    val stack = describeResult.stacks[0]
    val keepAliveLambdaArn = stack.outputs.find { it.outputKey == "KeepAliveLambdaArn" }?.outputValue
    val lambdaVersionArn = stack.outputs.find { it.outputKey == "LambdaVersionArn" }?.outputValue!!
    val status = StackStatus.fromValue(stack.stackStatus)
    if (!deployedStatuses.contains(status)) throw IllegalStateException("Stack status is ${stack.stackStatus}")
    return DeployResult(created, apiId(profile, apiName), lambdaVersionArn, keepAliveLambdaArn)
}

//--------------------------------------------------------------------------------------------------

private fun apiId(profile: AwsProfile, apiName: String): String {
    val apiGatewayClient = AmazonApiGatewayClientBuilder.standard()
        .withCredentials(profile.credentialsProvider)
        .withRegion(profile.region)
        .build()
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
