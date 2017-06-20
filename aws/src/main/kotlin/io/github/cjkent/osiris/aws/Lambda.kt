package io.github.cjkent.osiris.aws

import io.github.cjkent.osiris.api.API_COMPONENTS_CLASS
import io.github.cjkent.osiris.api.API_DEFINITION_CLASS
import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.DataNotFoundException
import io.github.cjkent.osiris.api.EncodedBody
import io.github.cjkent.osiris.api.HttpMethod
import io.github.cjkent.osiris.api.Params
import io.github.cjkent.osiris.api.Request
import io.github.cjkent.osiris.api.RequestHandler
import io.github.cjkent.osiris.server.ApiFactory

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
class ProxyRequest(
    var resource: String = "",
    var httpMethod: String = "",
    var headers: Map<String, String>? = mapOf(),
    var queryStringParameters: Map<String, String>? = mapOf(),
    var pathParameters: Map<String, String>? = mapOf(),
    var isBase64Encoded: Boolean = false,
    var body: String? = null
) {
    fun buildRequest(): Request = Request(
        HttpMethod.valueOf(httpMethod),
        resource,
        Params(headers),
        Params(queryStringParameters),
        Params(pathParameters),
        body,
        isBase64Encoded)
}

class ProxyLambda<T : ApiComponents> {

    private val components: T
    private val api: Api<T>

    init {
        val apiFactory = ApiFactory.create<T>(
            javaClass.classLoader,
            System.getenv(API_COMPONENTS_CLASS),
            System.getenv(API_DEFINITION_CLASS))
        components = apiFactory.createComponents()
        api = apiFactory.api
    }

    private val routeMap: Map<Pair<HttpMethod, String>, RequestHandler<T>> =
        api.routes.associateBy({ Pair(it.method, it.path) }, { it.handler })

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

    companion object {
        val handlerMethod = "io.github.cjkent.osiris.aws.ProxyLambda::handle"
    }
}
