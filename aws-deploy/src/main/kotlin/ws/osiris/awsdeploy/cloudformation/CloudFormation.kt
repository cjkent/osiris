package ws.osiris.awsdeploy.cloudformation

import com.amazonaws.services.apigateway.AmazonApiGateway
import com.amazonaws.services.apigateway.model.GetRestApisRequest
import com.amazonaws.services.apigateway.model.RestApi
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.CreateStackRequest
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStackResourceRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.model.ListStacksRequest
import com.amazonaws.services.cloudformation.model.StackStatus
import com.amazonaws.services.cloudformation.model.StackSummary
import com.amazonaws.services.cloudformation.model.UpdateStackRequest
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import ws.osiris.awsdeploy.AwsProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
    /** The ID of the API that was updated or created; the URL is https://$apiId.execute-api.$region.amazonaws.com/ */
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
    val stackSummaries = profile.cloudFormationClient.listAllStacks()
    val liveStacks = stackSummaries.filter { it.stackName == stackName && it.stackStatus != "DELETE_COMPLETE" }
    val (_, created) = if (liveStacks.isEmpty()) {
        val stackId = createStack(stackName, profile.cloudFormationClient, templateUrl); Pair(stackId, true)
    } else if (liveStacks.size > 1) {
        throw IllegalStateException("Found multiple stacks named '$stackName': $liveStacks")
    } else {
        val stackSummary = liveStacks[0]
        val status = StackStatus.fromValue(stackSummary.stackStatus)
        if (deleteStatuses.contains(status)) {
            deleteStack(stackName, profile.cloudFormationClient)
            val stackId = createStack(stackName, profile.cloudFormationClient, templateUrl)
            Pair(stackId, true)
        } else if (updateStatuses.contains(status)) {
            val stackId = updateStack(stackName, templateUrl, profile)
            Pair(stackId, false)
        } else if (createStatuses.contains(status)) {
            val stackId = createStack(stackName, profile.cloudFormationClient, templateUrl)
            Pair(stackId, true)
        } else {
            throw IllegalStateException("Unable to deploy stack '$stackName' with status ${stackSummary.stackStatus}")
        }
    }
    val apiStackResourceResult = profile.cloudFormationClient.describeStackResource(DescribeStackResourceRequest().apply {
        this.stackName = stackName
        this.logicalResourceId = "ApiStack"
    })
    val describeResult = profile.cloudFormationClient.describeStacks(DescribeStacksRequest().apply {
        this.stackName = apiStackResourceResult.stackResourceDetail.physicalResourceId
    })
    if (describeResult.stacks.size != 1) throw IllegalStateException("Multiple stacks found: ${describeResult.stacks}")
    val stack = describeResult.stacks[0]
    val keepAliveLambdaArn = stack.outputs.find { it.outputKey == "KeepAliveLambdaArn" }?.outputValue
    val lambdaVersionArn = stack.outputs.find { it.outputKey == "LambdaVersionArn" }?.outputValue!!
    val status = StackStatus.fromValue(stack.stackStatus)
    if (!deployedStatuses.contains(status)) throw IllegalStateException("Stack status is ${stack.stackStatus}")
    return DeployResult(created, apiId(apiName, profile), lambdaVersionArn, keepAliveLambdaArn)
}

/**
 * Extension function for the CloudFormation client that lists all stacks and deals with paging in the AWS SDK.
 */
fun AmazonCloudFormation.listAllStacks(): List<StackSummary> {
    fun listAllStacks(token: String?): List<StackSummary> {
        val result = listStacks(ListStacksRequest().apply { nextToken = token })
        return if (result.nextToken == null) {
            result.stackSummaries
        } else {
            result.stackSummaries + listAllStacks(result.nextToken)
        }
    }
    return listAllStacks(null)
}

//--------------------------------------------------------------------------------------------------

internal fun apiId(apiName: String, profile: AwsProfile): String {
    return profile.apiGatewayClient.getAllRestApis().find { it.name == apiName }?.id
        ?: throw IllegalStateException("No API found with name '$apiName'")
}

fun AmazonApiGateway.getAllRestApis(): List<RestApi> {
    fun restApis(token: String?): List<RestApi> {
        val result = getRestApis(GetRestApisRequest().apply { position = token })
        val apis = result.items
        return if (result.position == null) {
            apis
        } else {
            apis + restApis(result.position)
        }
    }
    return restApis(null)
}

/**
 * Returns the name of the API Gateway API for the given application and environment names.
 */
internal fun apiName(appName: String, envName: String?): String = if (envName == null) appName else "$appName.$envName"

private fun deleteStack(apiName: String, cloudFormationClient: AmazonCloudFormation) {
    log.info("Deleting stack '$apiName'")
    val describeResult = cloudFormationClient.describeStacks(DescribeStacksRequest().apply { stackName = apiName })
    val stackId = describeResult.stacks[0].stackId
    cloudFormationClient.deleteStack(DeleteStackRequest().apply { stackName = apiName })
    waitForStack(stackId, cloudFormationClient)
    log.info("Deleted stack '$apiName'")
}

private fun updateStack(
    appName: String,
    templateUrl: String,
    profile: AwsProfile
): String? {
    log.info("Updating stack '{}'", appName)
    val updateResult = profile.cloudFormationClient.updateStack(UpdateStackRequest().apply {
        stackName = appName
        templateURL = templateUrl
        setCapabilities(listOf(CAPABILITY_IAM))
    })
    waitForStack(updateResult.stackId, profile.cloudFormationClient)
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
        val stackSummary = cloudFormationClient.listAllStacks().filter { it.stackId == stackId }[0]
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

// --------------------------------------------------------------------------------------------------

/**
 * Represents a single CloudFormation YAML file.
 */
internal interface CloudFormationFile {
    fun write(templatesDir: Path)
}

/**
 * Represents the main generated CloudFormation template (`<app name>.template`).
 *
 * This contains all the main application resources. The only resources generated by Osiris that aren't
 * in this file are REST resources that won't fit within the 200-resources-per-file limit.
 *
 * If there are REST resources defined in another file then they are referenced from this file
 * as nested stacks.
 */
internal class MainCloudFormationFile internal constructor(
    private val fileName: String,
    private val parametersTemplate: ParametersTemplate,
    private val outputsTemplate: OutputsTemplate,
    private val apiTemplate: ApiTemplate,
    private val deploymentTemplate: DeploymentTemplate,
    private val restStackTemplates: List<RestStackTemplate>,
    vararg templates: Template?
) : CloudFormationFile {

    private val templates: List<Template?> = templates.toList()

    override fun write(templatesDir: Path) {
        val filePath = templatesDir.resolve(fileName)
        Files.newBufferedWriter(filePath, StandardOpenOption.CREATE).use { writer ->
            log.info("Writing CloudFormation template {}", filePath.toAbsolutePath())
            parametersTemplate.write(writer)
            writer.write("Resources:")
            for (template in templates.filterNotNull()) {
                log.debug("Writing template of type {}", template.javaClass.simpleName)
                template.write(writer)
            }
            log.debug("Writing root resource template")
            apiTemplate.write(writer)
            deploymentTemplate.write(writer, restStackTemplates.map { it.name }, apiTemplate.rootResource)
            for (template in restStackTemplates) {
                template.write(writer)
            }
            outputsTemplate.write(writer)
        }
    }
}

/**
 * Represents a CloudFormation template defining a nested stack containing nothing but API Gateway
 * REST resources and methods.
 */
internal class RestCloudFormationFile(
    private val fileName: String,
    private val templates: List<ResourceTemplate>,
    private val authorizerParam: Boolean
) : CloudFormationFile {

    override fun write(templatesDir: Path) {
        val filePath = templatesDir.resolve(fileName)
        Files.newBufferedWriter(filePath, StandardOpenOption.CREATE).use { writer ->
            log.info("Writing CloudFormation template {}", filePath.toAbsolutePath())
            // TODO this should go in a template impl for consistency
            @Language("yaml")
            val header = """
                Parameters:
                  Api:
                    Type: String
                    Description: ID of the API Gateway API
                  ParentResourceId:
                    Type: String
                    Description: ID of the API Gateway resource
                  LambdaArn:
                    Type: String
                    Description: ARN of the lambda function version
            """.trimIndent()
            @Language("yaml")
            val authorizerTemplate = """
            |
            |  Authorizer:
            |    Type: String
            |    Description: ID of the custom authorizer
            """.trimMargin()
            writer.write(header)
            if (authorizerParam) {
                writer.write(authorizerTemplate)
            }
            writer.write("\n")
            writer.write("Resources:")
            writer.write("\n")
            for (template in templates) {
                log.debug("Writing resource template with path part {}", template.pathPart)
                // All resources in this file must be children of the same resource (whose ID is a parameter)
                template.write(writer, "!Ref ParentResourceId", "LambdaArn")
            }
        }
    }
}
