package ws.osiris.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import org.slf4j.LoggerFactory
import ws.osiris.core.Api
import ws.osiris.core.Auth
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.DataNotFoundException
import ws.osiris.core.HttpMethod
import ws.osiris.core.LambdaRoute
import ws.osiris.core.Params
import ws.osiris.core.Request
import ws.osiris.core.RequestHandler
import java.util.Base64
import java.util.UUID
import com.amazonaws.services.lambda.runtime.RequestHandler as LambdaRequestHandler

/** The request attribute key used for the [Context] object passed into the lambda function by AWS. */
const val LAMBDA_CONTEXT_ATTR = "ws.osiris.aws.context"

/** The request attribute key used for the event object passed into the lambda function by AWS; it is a [Map] */
const val LAMBDA_EVENT_ATTR = "ws.osiris.aws.event"

/** The request attribute key used for the map of stage variables. */
const val STAGE_VARS_ATTR = "ws.osiris.aws.stagevariables"

/** The resource used to identify an event coming from the keep-alive lambda instead of API Gateway. */
const val KEEP_ALIVE_RESOURCE = "[keepAlive]"

/** The number of milliseconds for which to sleep after receiving a keep-alive request. */
const val KEEP_ALIVE_SLEEP = "sleepTimeMs"

/** Sleep for 200ms by default. */
private const val DEFAULT_KEEP_ALIVE_SLEEP = 200

private val log = LoggerFactory.getLogger("ws.osiris.aws")

data class ProxyResponse(
    val statusCode: Int = 200,
    val headers: Map<String, String> = mapOf(),
    // the weird name is required so Jackson serialises it into the JSON expected by API Gateway
    val isIsBase64Encoded: Boolean = false,
    val body: String? = null
)

@Suppress("UNCHECKED_CAST")
internal fun buildRequest(event: APIGatewayProxyRequestEvent, context: Context): Request {
    val body = event.body
    val isBase64Encoded = event.isBase64Encoded
    val requestBody: Any? = if (body is String && isBase64Encoded) Base64.getDecoder().decode(body) else body
    @Suppress("UNCHECKED_CAST")
    val requestContext = event.requestContext
    val stageVariables = event.stageVariables ?: mapOf()
    val attributes = mapOf(
        STAGE_VARS_ATTR to stageVariables,
        LAMBDA_CONTEXT_ATTR to context,
        LAMBDA_EVENT_ATTR to event
    )
    return Request(
        HttpMethod.valueOf(event.httpMethod),
        event.resource,
        Params(event.headers),
        Params(event.queryStringParameters),
        Params(event.pathParameters),
        Params(requestContextMap(requestContext) + identityMap(requestContext.identity)),
        requestBody,
        attributes
    )
}

private fun requestContextMap(context: APIGatewayProxyRequestEvent.ProxyRequestContext?): Map<String, String> {
    if (context == null) return mapOf()
    val contextMap = mutableMapOf<String, String>()
    if (context.accountId != null) contextMap["accountId"] = context.accountId
    if (context.stage != null) contextMap["stage"] = context.stage
    if (context.resourceId != null) contextMap["resourceId"] = context.resourceId
    if (context.requestId != null) contextMap["requestId"] = context.requestId
    if (context.resourcePath != null) contextMap["resourcePath"] = context.resourcePath
    if (context.apiId != null) contextMap["apiId"] = context.apiId
    if (context.path != null) contextMap["path"] = context.path
    context.authorizer?.filterValues { it is String }?.forEach { (k, v) -> contextMap[k] = v as String }
    return contextMap
}

private fun identityMap(identity: APIGatewayProxyRequestEvent.RequestIdentity?): Map<String, String> {
    if (identity == null) return mapOf()
    val map = mutableMapOf<String, String>()
    if (identity.cognitoIdentityPoolId != null) map["cognitoIdentityPoolId"] = identity.cognitoIdentityPoolId
    if (identity.accountId != null) map["accountId"] = identity.accountId
    if (identity.cognitoIdentityId != null) map["cognitoIdentityId"] = identity.cognitoIdentityId
    if (identity.caller != null) map["caller"] = identity.caller
    if (identity.apiKey != null) map["apiKey"] = identity.apiKey
    if (identity.sourceIp != null) map["sourceIp"] = identity.sourceIp
    if (identity.cognitoAuthenticationType != null) map["cognitoAuthenticationType"] = identity.cognitoAuthenticationType
    if (identity.cognitoAuthenticationProvider != null) map["cognitoAuthenticationProvider"] = identity.cognitoAuthenticationProvider
    if (identity.userArn != null) map["userArn"] = identity.userArn
    if (identity.userAgent != null) map["userAgent"] = identity.userAgent
    if (identity.user != null) map["user"] = identity.user
    if (identity.accessKey != null) map["accessKey"] = identity.accessKey
    return map
}

@Suppress("unused")
abstract class ProxyLambda<out T : ComponentsProvider>(api: Api<T>, private val components: T) :
    LambdaRequestHandler<APIGatewayProxyRequestEvent, ProxyResponse> {

    /** The HTTP request handlers, keyed by the HTTP method and path they handle. */
    private val routeMap: Map<Pair<HttpMethod, String>, RequestHandler<T>>

    /** Unique ID of the lambda instance; this helps figure out how many function instances are live. */
    private val id = UUID.randomUUID()

    init {
        log.debug("Creating ProxyLambda")
        routeMap = api.routes
            .filterIsInstance<LambdaRoute<T>>()
            .associateBy({ Pair(it.method, it.path) }, { it.handler })
        log.debug("Created routes")
    }

    override fun handleRequest(requestEvent: APIGatewayProxyRequestEvent, context: Context): ProxyResponse {
        log.debug("Function {} handling requestEvent {}", id, requestEvent)
        if (keepAlive(requestEvent)) return ProxyResponse()
        val request = buildRequest(requestEvent, context)
        log.debug("Request endpoint: {} {}", request.method, request.path)
        val handler = routeMap[Pair(request.method, request.path)] ?: throw DataNotFoundException()
        log.debug("Invoking handler")
        val response = handler.invoke(components, request)
        log.debug("Invoked handler")
        val proxyResponse = when (val body = response.body) {
            null -> ProxyResponse(response.status, response.headers.headerMap, false, null)
            is ByteArray -> ProxyResponse(response.status, response.headers.headerMap, true, encodeBinaryBody(body))
            is String -> ProxyResponse(response.status, response.headers.headerMap, false, body)
            else -> throw IllegalStateException("Response must contain null, a string or a ByteArray")
        }
        log.debug("Returning response")
        return proxyResponse
    }

    private fun encodeBinaryBody(byteArray: ByteArray): String =
        String(Base64.getEncoder().encode(byteArray), Charsets.UTF_8)

    /**
     * If the request is not a keep-alive request this function immediately returns false; otherwise it sleeps
     * for the time specified in the message and returns true
     */
    private fun keepAlive(requestEvent: APIGatewayProxyRequestEvent): Boolean {
        if (requestEvent.resource != KEEP_ALIVE_RESOURCE) return false
        val sleepTimeMs = requestEvent.headers[KEEP_ALIVE_SLEEP]?.toInt() ?: DEFAULT_KEEP_ALIVE_SLEEP
        log.debug("Keep-alive request received. Sleeping for {}ms. Function {}", sleepTimeMs, id)
        Thread.sleep(sleepTimeMs.toLong())
        return true
    }
}

/**
 * Represents the AWS authorisation type "AWS_IAM"; callers must include headers with credentials for
 * an AWS IAM user.
 */
object IamAuth : Auth {
    override val name: String = "AWS_IAM"
}

/**
 * Represents the AWS authorisation type "COGNITO_USER_POOLS"; the user must login to a Cognito user
 * pool and provide the token when calling the API.
 */
object CognitoUserPoolsAuth : Auth {
    override val name: String = "COGNITO_USER_POOLS"
}

/**
 * Represents the AWS authorisation type "CUSTOM"; the authorisation is carried out by custom logic in a lambda.
 */
object CustomAuth : Auth {
    override val name: String = "CUSTOM"
}
