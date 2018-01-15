package io.github.cjkent.osiris.aws

import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.Auth
import io.github.cjkent.osiris.core.Base64String
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.DataNotFoundException
import io.github.cjkent.osiris.core.EncodedBody
import io.github.cjkent.osiris.core.HttpMethod
import io.github.cjkent.osiris.core.LambdaRoute
import io.github.cjkent.osiris.core.Params
import io.github.cjkent.osiris.core.Request
import io.github.cjkent.osiris.core.RequestHandler
import java.util.Base64

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
    var body: String? = null
) {
    fun buildRequest(): Request {
        val localBody = body
        val requestBody: Any? = if (localBody is String && isIsBase64Encoded) Base64String(localBody) else localBody
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
            requestBody)
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

abstract class ProxyLambda<out T : ComponentsProvider>(api: Api<T>, private val components: T) {

    private val routeMap: Map<Pair<HttpMethod, String>, RequestHandler<T>> = api.routes
        .filter { it is LambdaRoute<T> }
        .map { it as LambdaRoute<T> }
        .associateBy({ Pair(it.method, it.path) }, { it.handler })

    fun handle(proxyRequest: ProxyRequest): ProxyResponse {
        val request = proxyRequest.buildRequest()
        val handler = routeMap[Pair(request.method, request.path)] ?: throw DataNotFoundException()
        val response = handler.invoke(components, request)
        val body = response.body
        return when (body) {
            is EncodedBody -> ProxyResponse(response.status, response.headers.headerMap, body.isBase64Encoded, body.body)
            is String -> ProxyResponse(response.status, response.headers.headerMap, false, body)
            else -> throw IllegalStateException("Response must contains a string or EncodedBody")
        }
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
