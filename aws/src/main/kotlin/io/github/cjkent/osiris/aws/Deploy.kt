package io.github.cjkent.osiris.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.apigateway.AmazonApiGateway
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.apigateway.model.CreateDeploymentRequest
import com.amazonaws.services.apigateway.model.CreateResourceRequest
import com.amazonaws.services.apigateway.model.CreateRestApiRequest
import com.amazonaws.services.apigateway.model.DeleteResourceRequest
import com.amazonaws.services.apigateway.model.GetResourcesRequest
import com.amazonaws.services.apigateway.model.GetRestApisRequest
import com.amazonaws.services.apigateway.model.PutIntegrationRequest
import com.amazonaws.services.apigateway.model.PutMethodRequest
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementAsyncClientBuilder
import com.amazonaws.services.identitymanagement.model.AttachRolePolicyRequest
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest
import com.amazonaws.services.lambda.AWSLambda
import com.amazonaws.services.lambda.AWSLambdaClientBuilder
import com.amazonaws.services.lambda.model.AddPermissionRequest
import com.amazonaws.services.lambda.model.CreateFunctionRequest
import com.amazonaws.services.lambda.model.Environment
import com.amazonaws.services.lambda.model.FunctionCode
import com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest
import com.amazonaws.services.lambda.model.UpdateFunctionConfigurationRequest
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest
import io.github.cjkent.osiris.api.API_COMPONENTS_CLASS
import io.github.cjkent.osiris.api.API_DEFINITION_CLASS
import io.github.cjkent.osiris.api.Auth
import io.github.cjkent.osiris.api.FixedRouteNode
import io.github.cjkent.osiris.api.RouteNode
import io.github.cjkent.osiris.api.VariableRouteNode
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.aws")

/**
 * Deploys a Lambda function to AWS and returns its ARN.
 */
fun deployLambda(
    region: String,
    credentialsProvider: AWSCredentialsProvider,
    apiName: String,
    fnName: String,
    fnRole: String?,
    memSizeMb: Int,
    jarFile: Path,
    componentsClass: KClass<*>,
    apiDefinitionClass: KClass<*>,
    envVars: Map<String, String>
): String {

    val roleArn = fnRole ?: createRole(credentialsProvider, region, apiName)
    val lambdaClient = AWSLambdaClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(region)
        .build()
    val randomAccessFile = RandomAccessFile(jarFile.toFile(), "r")
    val channel = randomAccessFile.channel
    val buffer = ByteBuffer.allocate(channel.size().toInt())
    channel.read(buffer)
    buffer.rewind()
    val classMap = mapOf(
        API_COMPONENTS_CLASS to componentsClass.jvmName,
        API_DEFINITION_CLASS to apiDefinitionClass.jvmName)
    val env = Environment().apply { variables = envVars + classMap }


    return deployLambdaFunction(fnName, memSizeMb, env, roleArn, buffer, lambdaClient)
}

// There is a race condition in AWS.
// If you try to create the lambda using a rule immediately after creating the role it can fail.
// https://stackoverflow.com/questions/37503075/invalidparametervalueexception-the-role-defined-for-the-function-cannot-be-assu
// Need to wait and retry.
private fun deployLambdaFunction(
    fnName: String,
    memSizeMb: Int,
    env: Environment,
    roleArn: String,
    buffer: ByteBuffer,
    lambdaClient: AWSLambda
): String {

    val maxRetries = 5
    val retryDelayMs = 5000L

    fun deployLambdaFunction(
        fnName: String,
        memSizeMb: Int,
        env: Environment,
        roleArn: String,
        buffer: ByteBuffer,
        lambdaClient: AWSLambda,
        retryCount: Int
    ): String = try {

        val listFunctionsResult = lambdaClient.listFunctions()
        val functionExists = listFunctionsResult.functions.any { it.functionName == fnName }
        if (functionExists) {
            val updateConfigurationRequest = UpdateFunctionConfigurationRequest().apply {
                functionName = fnName
                memorySize = memSizeMb
                handler = ProxyLambda.handlerMethod
                runtime = "java8"
                environment = env
                role = roleArn
            }
            val updateCodeRequest = UpdateFunctionCodeRequest().apply {
                functionName = fnName
                zipFile = buffer
                publish = true
            }
            log.info("Updating configuration of Lambda function '{}'", fnName)
            lambdaClient.updateFunctionConfiguration(updateConfigurationRequest)
            log.info("Updating code of Lambda function '{}'", fnName)
            val result = lambdaClient.updateFunctionCode(updateCodeRequest)
            result.functionArn
        } else {
            val functionCode = FunctionCode().apply { zipFile = buffer }
            val createFunctionRequest = CreateFunctionRequest().apply {
                functionName = fnName
                memorySize = memSizeMb
                handler = ProxyLambda.handlerMethod
                runtime = "java8"
                environment = env
                role = roleArn
                code = functionCode
                publish = true
            }
            log.info("Creating Lambda function '{}'", fnName)
            val result = lambdaClient.createFunction(createFunctionRequest)
            result.functionArn
        }
    } catch (e: Exception) {
        if (retryCount == maxRetries) throw e
        log.info("Failed to deploy lambda function. Retrying. Error: {}", e.message)
        Thread.sleep(retryDelayMs)
        deployLambdaFunction(fnName, memSizeMb, env, roleArn, buffer, lambdaClient, retryCount + 1)
    }
    return deployLambdaFunction(fnName, memSizeMb, env, roleArn, buffer, lambdaClient, 0)
}

/**
 * Deploys an API to API Gateway and returns its ID.
 */
fun deployApi(
    region: String,
    credentialsProvider: AWSCredentialsProvider,
    apiName: String,
    deploymentStage: String?,
    routeTree: RouteNode<*>,
    lambdaArn: String
): String {

    val apiGateway = AmazonApiGatewayClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(region)
        .build()
    val restApisResult = apiGateway.getRestApis(GetRestApisRequest())
    // can only get an API by ID - getting by name requires getting all of them and filtering
    val api = restApisResult.items.find { it.name == apiName }
    val apiId = if (api != null) {
        log.info("Updating REST API '{}'", apiName)
        api.id
    } else {
        log.info("Creating REST API '{}'", apiName)
        apiGateway.createRestApi(CreateRestApiRequest().apply { name = apiName }).id
    }
    val rootResourceId = rootResource(apiGateway, apiId)
    createIntegrations(apiGateway, apiId, routeTree, rootResourceId, region, lambdaArn)
    createChildResources(apiGateway, apiId, routeTree, rootResourceId, region, lambdaArn)
    if (deploymentStage != null) {
        log.info("Deploying REST API '{}' to stage '{}'", apiName, deploymentStage)
        val deploymentRequest = CreateDeploymentRequest().apply { restApiId = apiId; stageName = deploymentStage }
        apiGateway.createDeployment(deploymentRequest)
    }
    return apiId

    // for initial impl, reuse the same API if the name exists, but delete all the resources and start from scratch
    // maybe later can build up a model by querying API gateway and only change what's necessary
    // can do a dry run without making any changes like Terraform
    // would need a parallel model for the API Gateway API, similar to RouteNode but including the ID and no handler
}

// TODO include the function version or alias in the ARN?
fun addPermissions(credentialsProvider: AWSCredentialsProvider, apiId: String, region: String, lambdaArn: String) {
    val lambdaClient = AWSLambdaClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region).build()
    val securityService = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(credentialsProvider).build()
    val accountId = securityService.getCallerIdentity(GetCallerIdentityRequest()).account

    val permissionRequest = AddPermissionRequest().apply {
        functionName = lambdaArn
        action = "lambda:InvokeFunction"
        principal = "apigateway.amazonaws.com"
        sourceArn = "arn:aws:execute-api:$region:$accountId:$apiId/*"
        statementId = UUID.randomUUID().toString()
    }
    lambdaClient.addPermission(permissionRequest)
}

//===================================================================================================================

private fun createResource(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: RouteNode<*>,
    parentResourceId: String,
    region: String,
    lambdaArn: String
) {

    val segmentName = when (node) {
        is FixedRouteNode<*> -> node.segment.pathPart
        is VariableRouteNode<*> -> "{${node.segment.variableName}}"
    }
    val createResourceRequest = CreateResourceRequest().apply {
        restApiId = apiId
        parentId = parentResourceId
        pathPart = segmentName
    }
    val result = apiGateway.createResource(createResourceRequest)
    log.debug("Created resource {}", result.path)
    val createdResourceId = result.id
    createIntegrations(apiGateway, apiId, node, createdResourceId, region, lambdaArn)
    createChildResources(apiGateway, apiId, node, createdResourceId, region, lambdaArn)
}

private fun createIntegrations(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: RouteNode<*>,
    nodeResourceId: String,
    region: String,
    lambdaArn: String
) {

    for ((method, route) in node.routes) {
        val auth = route.auth ?: Auth.None
        val methodRequest = PutMethodRequest().apply {
            restApiId = apiId
            resourceId = nodeResourceId
            httpMethod = method.name
            authorizationType = auth.name
            authorizerId = (route.auth as? Auth.Custom)?.authorizerId
        }
        apiGateway.putMethod(methodRequest)

        val arn = "arn:aws:apigateway:$region:lambda:path/2015-03-31/functions/$lambdaArn/invocations"
        val integrationRequest = PutIntegrationRequest().apply {
            restApiId = apiId
            resourceId = nodeResourceId
            type = "AWS_PROXY"
            integrationHttpMethod = "POST"
            httpMethod = method.name
            uri = arn
        }
        apiGateway.putIntegration(integrationRequest)
    }
}

private fun createChildResources(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: RouteNode<*>,
    parentResourceId: String,
    region: String,
    lambdaArn: String
) {

    for ((_, childNode) in node.fixedChildren) {
        createResource(apiGateway, apiId, childNode, parentResourceId, region, lambdaArn)
    }
    node.variableChild?.let { child -> createResource(apiGateway, apiId, child, parentResourceId, region, lambdaArn) }
}

/**
 * Creates a role with permission to invoke the Lambda and returns its ARN.
 *
 * The role name is `${API name}-execute-lambda`.
 */
private fun createRole(credentialsProvider: AWSCredentialsProvider, region: String, apiName: String): String {
    val iamClient = AmazonIdentityManagementAsyncClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(region)
        .build()
    val newRoleName = "$apiName-execute-lambda"
    val existingRole = iamClient.listRoles().roles.find { it.roleName == newRoleName }
    return if (existingRole != null) {
        log.debug("Found existing role {}, {}", existingRole.roleName, existingRole.arn)
        existingRole.arn
    } else {
        val roleRequest = CreateRoleRequest().apply {
            roleName = newRoleName
            assumeRolePolicyDocument = ASSUME_ROLE_POLICY_DOCUMENT
            description = "Execute Lambda function for REST API '$apiName'"
        }
        val role = iamClient.createRole(roleRequest).role

        val rolePolicyRequest = AttachRolePolicyRequest().apply {
            roleName = newRoleName
            policyArn = "arn:aws:iam::aws:policy/AWSLambdaExecute"
        }
        iamClient.attachRolePolicy(rolePolicyRequest)

        log.info("Created role {}, {}", role.roleName, role.arn)
        role.arn
    }
}

/** The policy document attached to the role created for executing the lambda. */
private const val ASSUME_ROLE_POLICY_DOCUMENT =
"""{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "lambda.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}"""

/**
 * Returns the ID of the root resource of the API.
 *
 * All other resources are deleted so the returned ID identifies an empty resource
 */
private fun rootResource(apiGateway: AmazonApiGateway, apiId: String): String {
    val resourcesResult = apiGateway.getResources(GetResourcesRequest().apply { restApiId = apiId })
    // API Gateway APIs always have a root resource that can't be deleted so this is safe
    val rootResourceId = resourcesResult.items.find { it.parentId == null }!!.id
    val childrenOfRoot = resourcesResult.items.filter { it.parentId == rootResourceId }
    for (resource in childrenOfRoot) {
        log.debug("Deleting resource {}", resource.path)
        apiGateway.deleteResource(DeleteResourceRequest().apply { restApiId = apiId; resourceId = resource.id })
    }
    return rootResourceId
}
