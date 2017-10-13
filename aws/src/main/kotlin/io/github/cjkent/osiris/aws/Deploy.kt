package io.github.cjkent.osiris.aws

import com.amazonaws.ClientConfiguration
import com.amazonaws.PredefinedClientConfigurations
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.retry.PredefinedBackoffStrategies
import com.amazonaws.retry.PredefinedRetryPolicies
import com.amazonaws.retry.RetryPolicy
import com.amazonaws.services.apigateway.AmazonApiGateway
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.apigateway.model.CreateDeploymentRequest
import com.amazonaws.services.apigateway.model.CreateResourceRequest
import com.amazonaws.services.apigateway.model.CreateRestApiRequest
import com.amazonaws.services.apigateway.model.DeleteMethodRequest
import com.amazonaws.services.apigateway.model.DeleteResourceRequest
import com.amazonaws.services.apigateway.model.GetResourcesRequest
import com.amazonaws.services.apigateway.model.GetRestApisRequest
import com.amazonaws.services.apigateway.model.GetStagesRequest
import com.amazonaws.services.apigateway.model.PutIntegrationRequest
import com.amazonaws.services.apigateway.model.PutIntegrationResponseRequest
import com.amazonaws.services.apigateway.model.PutMethodRequest
import com.amazonaws.services.apigateway.model.PutMethodResponseRequest
import com.amazonaws.services.apigateway.model.TooManyRequestsException
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
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest
import io.github.cjkent.osiris.core.API_COMPONENTS_CLASS
import io.github.cjkent.osiris.core.API_DEFINITION_CLASS
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.Auth
import io.github.cjkent.osiris.core.FixedRouteNode
import io.github.cjkent.osiris.core.RouteNode
import io.github.cjkent.osiris.core.StaticRouteNode
import io.github.cjkent.osiris.core.VariableRouteNode
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Locale
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
    fnName: String,
    roleArn: String,
    memSizeMb: Int,
    timeoutSec: Int,
    jarFile: Path,
    componentsClass: KClass<*>,
    apiDefinitionClass: KClass<*>,
    envVars: Map<String, String>
): String {

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

    return deployLambdaFunction(fnName, memSizeMb, timeoutSec, env, roleArn, buffer, lambdaClient)
}

// There is a race condition in AWS.
// If you try to create the lambda using a rule immediately after creating the role it can fail.
// https://stackoverflow.com/questions/37503075/invalidparametervalueexception-the-role-defined-for-the-function-cannot-be-assu
// Need to wait and retry.
private fun deployLambdaFunction(
    fnName: String,
    memSizeMb: Int,
    timeoutSec: Int,
    env: Environment,
    roleArn: String,
    buffer: ByteBuffer,
    lambdaClient: AWSLambda
): String {

    val maxRetries = 5
    val retryDelayMs = 5000L

    fun deployLambdaFunction(retryCount: Int): String = try {
        val listFunctionsResult = lambdaClient.listFunctions()
        val functionExists = listFunctionsResult.functions.any { it.functionName == fnName }
        if (functionExists) {
            log.info("Updating configuration of Lambda function '{}'", fnName)
            lambdaClient.updateFunctionConfiguration(UpdateFunctionConfigurationRequest().apply {
                functionName = fnName
                memorySize = memSizeMb
                timeout = timeoutSec
                handler = ProxyLambda.handlerMethod
                runtime = "java8"
                environment = env
                role = roleArn
            })
            log.info("Updating code of Lambda function '{}'", fnName)
            val result = lambdaClient.updateFunctionCode(UpdateFunctionCodeRequest().apply {
                functionName = fnName
                zipFile = buffer
                publish = true
            })
            result.functionArn
        } else {
            val functionCode = FunctionCode().apply { zipFile = buffer }
            log.info("Creating Lambda function '{}'", fnName)
            val result = lambdaClient.createFunction(CreateFunctionRequest().apply {
                functionName = fnName
                memorySize = memSizeMb
                timeout = timeoutSec
                handler = ProxyLambda.handlerMethod
                runtime = "java8"
                environment = env
                role = roleArn
                code = functionCode
                publish = true
            })
            "${result.functionArn}:${result.version}"
        }
    } catch (e: Exception) {
        if (retryCount == maxRetries) throw e
        log.info("Failed to deploy lambda function. Retrying. Error: {}", e.message)
        Thread.sleep(retryDelayMs)
        deployLambdaFunction(retryCount + 1)
    }
    return deployLambdaFunction(0)
}

/**
 * The ID of a deployed API and the names of the stages that were deployed.
 */
data class DeployResult(val apiId: String, val stagesNames: Set<String>)

/**
 * Deploys an API to API Gateway and returns its ID.
 */
fun deployApi(
    region: String,
    credentialsProvider: AWSCredentialsProvider,
    apiName: String,
    api: Api<*>,
    stages: Map<String, Stage>,
    lambdaArn: String,
    roleArn: String,
    staticFilesBucket: String?
): DeployResult {

    val apiGateway = AmazonApiGatewayClientBuilder.standard()
        .withClientConfiguration(apiGatewayClientConfig())
        .withCredentials(credentialsProvider)
        .withRegion(region)
        .build()
    val restApisResult = apiGateway.getRestApis(GetRestApisRequest())
    // can only get an API by ID - getting by name requires getting all of them and filtering
    val existingApi = restApisResult.items.find { it.name == apiName }
    val apiId = if (existingApi != null) {
        log.info("Updating REST API '{}'", apiName)
        existingApi.id
    } else {
        log.info("Creating REST API '{}'", apiName)
        apiGateway.createRestApi(CreateRestApiRequest().apply { name = apiName }).id
    }
    val routeTree = RouteNode.create(api)
    val rootResourceId = rootResource(apiGateway, apiId)
    createIntegrations(apiGateway, apiId, routeTree, rootResourceId, region, lambdaArn, roleArn, staticFilesBucket)
    createChildResources(apiGateway, apiId, routeTree, rootResourceId, region, lambdaArn, roleArn, staticFilesBucket)
    val existingStages = apiGateway.getStages(GetStagesRequest().apply { restApiId = apiId }).item
    val existingStageNames = existingStages.map { it.stageName }.toSet()
    val stagesToUpdate = stages.filter { (name, stage) -> existingStageNames.contains(name) && stage.deployOnUpdate }
    val stagesToCreate = stages.filter { (name, _) -> !existingStageNames.contains(name) }
    for ((name, stage) in stagesToUpdate) {
        log.info("Updating REST API '{}' in stage '{}'", apiName, name)
        apiGateway.createDeployment(CreateDeploymentRequest().apply {
            restApiId = apiId
            stageName = name
            variables = stage.variables
            description = stage.description
        })
    }
    for ((name, stage) in stagesToCreate) {
        log.info("Deploying REST API '{}' to stage '{}'", apiName, name)
        apiGateway.createDeployment(CreateDeploymentRequest().apply {
            restApiId = apiId
            stageName = name
            variables = stage.variables
            description = stage.description
        })
    }
    return DeployResult(apiId, stagesToCreate.keys + stagesToUpdate.keys)
}

private fun apiGatewayClientConfig(): ClientConfiguration {
    val backoffStrategy = PredefinedBackoffStrategies.ExponentialBackoffStrategy(200, 3000)
    val retryCondition: RetryPolicy.RetryCondition = RetryPolicy.RetryCondition { request, exception, retriesAttempted ->
        (exception is TooManyRequestsException) ||
            PredefinedRetryPolicies.SDKDefaultRetryCondition().shouldRetry(request, exception, retriesAttempted)
    }
    val customRetryPolicy = RetryPolicy(retryCondition, backoffStrategy, 10, false)
    return PredefinedClientConfigurations.defaultConfig().apply { retryPolicy = customRetryPolicy }
}

// TODO include the function version or alias in the ARN?
fun addPermissions(credentialsProvider: AWSCredentialsProvider, apiId: String, region: String, lambdaArn: String) {
    val lambdaClient = AWSLambdaClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region).build()
    val securityService = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(credentialsProvider).build()
    val accountId = securityService.getCallerIdentity(GetCallerIdentityRequest()).account

    lambdaClient.addPermission(AddPermissionRequest().apply {
        functionName = lambdaArn
        action = "lambda:InvokeFunction"
        principal = "apigateway.amazonaws.com"
        sourceArn = "arn:aws:execute-api:$region:$accountId:$apiId/*"
        statementId = UUID.randomUUID().toString()
    })
}

//===================================================================================================================

private fun createResource(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: RouteNode<*>,
    parentResourceId: String,
    region: String,
    lambdaArn: String,
    roleArn: String,
    staticFilesBucket: String?
) {

    val segmentName = when (node) {
        is FixedRouteNode<*> -> node.name
        is StaticRouteNode<*> -> node.name
        is VariableRouteNode<*> -> "{${node.name}}"
    }
    val result = apiGateway.createResource(CreateResourceRequest().apply {
        restApiId = apiId
        parentId = parentResourceId
        pathPart = segmentName
    })
    log.debug("Created resource {}", result.path)
    val createdResourceId = result.id
    createIntegrations(apiGateway, apiId, node, createdResourceId, region, lambdaArn, roleArn, staticFilesBucket)
    createChildResources(apiGateway, apiId, node, createdResourceId, region, lambdaArn, roleArn, staticFilesBucket)
}

private fun createIntegrations(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: RouteNode<*>,
    nodeResourceId: String,
    region: String,
    lambdaArn: String,
    roleArn: String,
    staticFilesBucket: String?
) {

    if (node is StaticRouteNode<*>) {
        createStaticEndpoint(apiGateway, apiId, node, nodeResourceId, region, roleArn, staticFilesBucket)
    } else {
        for ((method, pair) in node.handlers) {
            val auth = pair.second ?: Auth.None
            apiGateway.putMethod(PutMethodRequest().apply {
                restApiId = apiId
                resourceId = nodeResourceId
                httpMethod = method.name
                authorizationType = auth.name
                authorizerId = (pair.second as? Auth.Custom)?.authorizerId
            })
            val arn = "arn:aws:apigateway:$region:lambda:path/2015-03-31/functions/$lambdaArn/invocations"
            apiGateway.putIntegration(PutIntegrationRequest().apply {
                restApiId = apiId
                resourceId = nodeResourceId
                type = "AWS_PROXY"
                integrationHttpMethod = "POST"
                httpMethod = method.name
                uri = arn
            })
        }
    }
}

private fun createStaticEndpoint(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: StaticRouteNode<*>,
    nodeResourceId: String,
    region: String,
    roleArn: String,
    staticFilesBucket: String?
) {

    if (node.indexFile != null) {
        // create a method, integration and responses for the index file
        log.debug("Creating index file GET method on node '{}', ID {}", node.name, nodeResourceId)
        apiGateway.putMethod(PutMethodRequest().apply {
            restApiId = apiId
            resourceId = nodeResourceId
            httpMethod = "GET"
            authorizationType = node.auth.name
            authorizerId = (node.auth as? Auth.Custom)?.authorizerId
        })
        log.debug("Creating index file integration. file {}, bucket {}", node.indexFile, staticFilesBucket)
        apiGateway.putIntegration(PutIntegrationRequest().apply {
            restApiId = apiId
            resourceId = nodeResourceId
            type = "AWS"
            integrationHttpMethod = "GET"
            httpMethod = "GET"
            uri = "arn:aws:apigateway:$region:s3:path/$staticFilesBucket/${node.indexFile}"
            credentials = roleArn
        })
    }
    create200Responses(apiGateway, apiId, nodeResourceId)
    createResponses(apiGateway, apiId, nodeResourceId, 403)
    // create a resource with a greedy path to match any static file
    val proxyResourceId = apiGateway.createResource(CreateResourceRequest().apply {
        restApiId = apiId
        parentId = nodeResourceId
        pathPart = "{proxy+}"
    }).id
    log.debug("Created greedy resource for static files with ID {}", proxyResourceId)
    apiGateway.putMethod(PutMethodRequest().apply {
        restApiId = apiId
        resourceId = proxyResourceId
        httpMethod = "GET"
        authorizationType = node.auth.name
        authorizerId = (node.auth as? Auth.Custom)?.authorizerId
        requestParameters = mapOf("method.request.path.proxy" to true)
    })
    apiGateway.putIntegration(PutIntegrationRequest().apply {
        restApiId = apiId
        resourceId = proxyResourceId
        type = "AWS"
        integrationHttpMethod = "GET"
        httpMethod = "GET"
        uri = "arn:aws:apigateway:$region:s3:path/$staticFilesBucket/{object}"
        requestParameters = mapOf("integration.request.path.object" to "method.request.path.proxy")
        credentials = roleArn
    })
    create200Responses(apiGateway, apiId, proxyResourceId)
    createResponses(apiGateway, apiId, proxyResourceId, 403)
    createResponses(apiGateway, apiId, proxyResourceId, 404)
}

private fun createResponses(apiGateway: AmazonApiGateway, apiId: String, nodeResourceId: String, status: Int) {
    val statusStr = status.toString()
    log.debug("Putting {} response for GET method on resource ID {}", statusStr, nodeResourceId)
    apiGateway.putMethodResponse(PutMethodResponseRequest().apply {
        restApiId = apiId
        resourceId = nodeResourceId
        httpMethod = "GET"
        statusCode = statusStr
    })
    log.debug("Putting {} integration response for GET method on resource ID {}", statusStr, nodeResourceId)
    apiGateway.putIntegrationResponse(PutIntegrationResponseRequest().apply {
        restApiId = apiId
        resourceId = nodeResourceId
        httpMethod = "GET"
        statusCode = statusStr
        selectionPattern = statusStr
    })
}

private fun create200Responses(apiGateway: AmazonApiGateway, apiId: String, nodeResourceId: String) {
    val methodParameters = mapOf(
        "method.response.header.Content-Type" to true,
        "method.response.header.Content-Length" to true
    )
    log.debug("Putting 200 response for GET method on resource ID {}", nodeResourceId)
    apiGateway.putMethodResponse(PutMethodResponseRequest().apply {
        restApiId = apiId
        resourceId = nodeResourceId
        httpMethod = "GET"
        statusCode = "200"
        responseParameters = methodParameters
    })
    val headerMappings = mapOf(
        "method.response.header.Content-Type" to "integration.response.header.Content-Type",
        "method.response.header.Content-Length" to "integration.response.header.Content-Length"
    )
    log.debug("Putting 200 integration response for GET method on resource ID {}", nodeResourceId)
    apiGateway.putIntegrationResponse(PutIntegrationResponseRequest().apply {
        restApiId = apiId
        resourceId = nodeResourceId
        httpMethod = "GET"
        statusCode = "200"
        responseParameters = headerMappings
    })
}

private fun createChildResources(
    apiGateway: AmazonApiGateway,
    apiId: String,
    node: RouteNode<*>,
    parentResourceId: String,
    region: String,
    lambdaArn: String,
    roleArn: String,
    staticFilesBucket: String?
) {

    for ((_, childNode) in node.fixedChildren) {
        createResource(apiGateway, apiId, childNode, parentResourceId, region, lambdaArn, roleArn, staticFilesBucket)
    }
    node.variableChild?.let {
        createResource(apiGateway, apiId, it, parentResourceId, region, lambdaArn, roleArn, staticFilesBucket)
    }
}

/**
 * Creates a role with permission to invoke the Lambda and returns its ARN.
 *
 * The role name is `${API name}-execute-lambda`.
 */
fun createRole(credentialsProvider: AWSCredentialsProvider, region: String, apiName: String): String {
    val iamClient = AmazonIdentityManagementAsyncClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(region)
        .build()
    val existingRole = iamClient.listRoles().roles.find { it.roleName == apiName }
    return if (existingRole != null) {
        log.debug("Found existing role {}, {}", existingRole.roleName, existingRole.arn)
        existingRole.arn
    } else {
        val roleResult = iamClient.createRole(CreateRoleRequest().apply {
            roleName = apiName
            assumeRolePolicyDocument = ASSUME_ROLE_POLICY_DOCUMENT
            description = "Execute Lambda function for REST API '$apiName'"
        })
        val role = roleResult.role
        iamClient.attachRolePolicy(AttachRolePolicyRequest().apply {
            roleName = apiName
            // TODO need a policy that allows listing the static S3 bucket (if it exists)
            // This one is a bit coarse-grained and unknown files return 403 not 404
            policyArn = "arn:aws:iam::aws:policy/AWSLambdaExecute"
        })
        log.info("Created role {}, {}", role.roleName, role.arn)
        role.arn
    }
}

/**
 * Creates an S3 bucket to hold static files.
 *
 * The bucket name is `${API name}.static-files`, converted to lower case.
 *
 * If the bucket already exists the function does nothing.
 */
fun createStaticFilesBucket(credentialsProvider: AWSCredentialsProvider, region: String, apiName: String): String {
    val s3Client = AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region).build()
    val bucketName = (apiName + ".static-files").toLowerCase(Locale.ENGLISH)
    if (!s3Client.doesBucketExist(bucketName)) {
        s3Client.createBucket(bucketName)
        log.info("Created S3 bucket '$bucketName'")
    } else {
        log.info("Using existing S3 bucket '$bucketName'")
    }
    return bucketName
}

/**
 * Uploads a file to an S3 bucket.
 *
 * The file should be under `baseDir` on the filesystem. The S3 key for the file will be the relative path
 * from the base directory to the file.
 *
 * For example, if `baseDir` is `/foo/bar` and the file is `/foo/bar/baz/qux.txt` then the file will be
 * uploaded to S3 with the key `baz/qux.txt
 */
fun uploadFile(
    credentialsProvider: AWSCredentialsProvider,
    region: String,
    bucketName: String,
    baseDir: Path,
    file: Path
) {

    val s3Client = AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region).build()
    val key = baseDir.relativize(file).toString()
    s3Client.putObject(bucketName, key, file.toFile())
    log.debug("Uploaded file {} to S3 bucket {}", file, bucketName)
}

/** The policy document attached to the role created for executing the lambda. */
@Language("JSON")
private const val ASSUME_ROLE_POLICY_DOCUMENT =
"""{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": [
          "lambda.amazonaws.com",
          "apigateway.amazonaws.com"
        ]
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
    // TODO this paginates the resources so might fail for APIs with a lot of resources
    // The maximum we can request at one time is 500. That should do for now
    val resourcesResult = apiGateway.getResources(GetResourcesRequest().apply { restApiId = apiId; limit = 500 })
    // API Gateway APIs always have a root resource that can't be deleted so this is safe
    val rootResource = resourcesResult.items.find { it.parentId == null }!!
    val childrenOfRoot = resourcesResult.items.filter { it.parentId == rootResource.id }
    for (resource in childrenOfRoot) {
        log.debug("Deleting resource {}", resource.path)
        apiGateway.deleteResource(DeleteResourceRequest().apply { restApiId = apiId; resourceId = resource.id })
    }
    val rootMethods = rootResource.resourceMethods?.keys ?: setOf<String>()
    // Need to delete all methods from the root resource
    // Otherwise it's impossible to overwrite an API serving static files from "/"
    for (method in rootMethods) {
        log.debug("Deleting method {} from the root resource", method)
        apiGateway.deleteMethod(DeleteMethodRequest().apply {
            restApiId = apiId
            resourceId = rootResource.id
            httpMethod = method
        })
    }
    return rootResource.id
}

data class Stage(val variables: Map<String, String>, val deployOnUpdate: Boolean, val description: String)
