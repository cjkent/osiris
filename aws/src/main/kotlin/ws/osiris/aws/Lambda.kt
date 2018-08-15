package ws.osiris.aws

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

private val log = LoggerFactory.getLogger("ws.osiris.aws")

data class ProxyResponse(
    val statusCode: Int = 200,
    val headers: Map<String, String> = mapOf(),
    // the weird name is required so Jackson serialises it into the JSON expected by API Gateway
    val isIsBase64Encoded: Boolean = false,
    val body: String? = null
)

/**
 * The input to an AWS Lambda function when invoked by API Gateway using the proxy integration type.
 */
@Suppress("MemberVisibilityCanPrivate")
class ProxyRequest(
    /** The path to the endpoint relative to the root of the API. */
    var resource: String = "",
    /** The path to the endpoint relative to the domain root; includes any base path mapping applied to the API. */
    var path: String = "",
    var httpMethod: String = "",
    var headers: Map<String, String>? = mapOf(),
    var queryStringParameters: Map<String, String>? = mapOf(),
    var pathParameters: Map<String, String>? = mapOf(),
    /** Indicates whether the body is base 64 encoded binary data; the weird name ensures it's deserialised correctly */
    var isIsBase64Encoded: Boolean = false,
    var requestContext: Map<String, Any> = mapOf(),
    var stageVariables: Map<String, String>? = mapOf(),
    var body: String? = null
) {
    fun buildRequest(): Request {
        val localBody = body
        val requestBody: Any? = if (localBody is String && isIsBase64Encoded) {
            Base64.getDecoder().decode(localBody)
        } else {
            localBody
        }
        @Suppress("UNCHECKED_CAST")
        val identityMap = requestContext["identity"] as Map<String, String>
        val requestContextMap = requestContext.filterValues { it is String }.mapValues { (_, v) -> v as String }
        return Request(
            HttpMethod.valueOf(httpMethod),
            resource,
            Params(headers),
            Params(queryStringParameters),
            Params(pathParameters),
            Params(requestContextMap + identityMap),
            requestBody,
            mapOf("stageVariables" to (stageVariables ?: mapOf()))
        )
    }

    /**
     * Returns a byte array containing the binary data in the body; returns null if the body is null or
     * not base 64 encoded.
     */
    val binaryBody: ByteArray? get() {
        if (!isIsBase64Encoded || body == null) return null
        return Base64.getDecoder().decode(body)
    }
}

@Suppress("unused")
abstract class ProxyLambda<out T : ComponentsProvider>(api: Api<T>, private val components: T) {

    private val routeMap: Map<Pair<HttpMethod, String>, RequestHandler<T>>

    init {
        log.debug("Creating ProxyLambda")
        routeMap = api.routes
            .filter { it is LambdaRoute<T> }
            .map { it as LambdaRoute<T> }
            .associateBy({ Pair(it.method, it.path) }, { it.handler })
        log.debug("Created routes")
    }

    fun handle(proxyRequest: ProxyRequest): ProxyResponse {
        log.debug("Handling request: {}", proxyRequest)
        val request = proxyRequest.buildRequest()
        log.debug("Request endpoint: {} {}", request.method, request.path)
        val handler = routeMap[Pair(request.method, request.path)] ?: throw DataNotFoundException()
        log.debug("Invoking handler")
        val response = handler.invoke(components, request)
        log.debug("Invoked handler")
        val body = response.body
        val proxyResponse = when (body) {
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
