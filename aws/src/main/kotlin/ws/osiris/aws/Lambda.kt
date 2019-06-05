package ws.osiris.aws

import com.amazonaws.services.lambda.runtime.Context
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

/** The request attribute key used for the [Context] object passed into the lambda function by AWS. */
const val LAMBDA_CONTEXT_ATTR = "ws.osiris.aws.context"

/** The request attribute key used for the event object passed into the lambda function by AWS; it is a [Map] */
const val LAMBDA_EVENT_ATTR = "ws.osiris.aws.event"

/** The request attribute key used for the map of stage variables. */
const val STAGE_VARS_ATTR = "ws.osiris.aws.stagevariables"

private val log = LoggerFactory.getLogger("ws.osiris.aws")

data class ProxyResponse(
    val statusCode: Int = 200,
    val headers: Map<String, String> = mapOf(),
    // the weird name is required so Jackson serialises it into the JSON expected by API Gateway
    val isIsBase64Encoded: Boolean = false,
    val body: String? = null
)

@Suppress("UNCHECKED_CAST")
internal fun buildRequest(requestJson: Map<*, *>, context: Context): Request {
    val body = requestJson["body"]
    val isBase64Encoded = requestJson["isBase64Encoded"] as Boolean
    val requestBody: Any? = if (body is String && isBase64Encoded) Base64.getDecoder().decode(body) else body
    @Suppress("UNCHECKED_CAST")
    val requestContext = requestJson["requestContext"] as Map<String, *>
    val identityMap = requestContext["identity"] as Map<String, String>
    val requestContextMap = requestContext.filterValues { it is String }.mapValues { (_, v) -> v as String }
    val stageVariables = requestJson["stageVariables"] as Map<String, String>? ?: mapOf()
    val attributes = mapOf(
        STAGE_VARS_ATTR to stageVariables,
        LAMBDA_CONTEXT_ATTR to context,
        LAMBDA_EVENT_ATTR to requestJson
    )
    return Request(
        HttpMethod.valueOf(requestJson["httpMethod"] as String),
        requestJson["resource"] as String,
        Params(requestJson["headers"] as Map<String, String>?),
        Params(requestJson["queryStringParameters"] as Map<String, String>?),
        Params(requestJson["pathParameters"] as Map<String, String>?),
        Params(requestContextMap + identityMap),
        requestBody,
        attributes
    )
}

@Suppress("unused")
abstract class ProxyLambda<out T : ComponentsProvider>(api: Api<T>, private val components: T) {

    /** The HTTP request handlers, keyed by the HTTP method and path they handle. */
    private val routeMap: Map<Pair<HttpMethod, String>, RequestHandler<T>>

    /** Unique ID of the lambda instance; this helps figure out how many function instances are live. */
    private val id = UUID.randomUUID()

    init {
        log.debug("Creating ProxyLambda")
        routeMap = api.routes
            .filter { it is LambdaRoute<T> }
            .map { it as LambdaRoute<T> }
            .associateBy({ Pair(it.method, it.path) }, { it.handler })
        log.debug("Created routes")
    }

    fun handle(requestJson: Map<*, *>, context: Context): ProxyResponse {
        log.debug("Function {} handling request {}", id, requestJson)
        if (keepAlive(requestJson)) return ProxyResponse()
        val request = buildRequest(requestJson, context)
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
    private fun keepAlive(requestJson: Map<*, *>): Boolean {
        val keepAliveJson = requestJson["keepAlive"] as Map<*, *>? ?: return false
        val sleepTimeMs = keepAliveJson["sleepTimeMs"] as Int
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
