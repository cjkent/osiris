package io.github.cjkent.osiris.aws

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cjkent.osiris.api.API_COMPONENTS_CLASS
import io.github.cjkent.osiris.api.API_DEFINITION_CLASS
import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.ContentTypes
import io.github.cjkent.osiris.api.DataNotFoundException
import io.github.cjkent.osiris.api.HttpException
import io.github.cjkent.osiris.api.HttpHeaders
import io.github.cjkent.osiris.api.HttpMethod
import io.github.cjkent.osiris.api.Params
import io.github.cjkent.osiris.api.Request
import io.github.cjkent.osiris.api.RequestHandler
import io.github.cjkent.osiris.server.ApiFactory
import io.github.cjkent.osiris.server.encodeResponseBody

data class ProxyResponse(
    val statusCode: Int = 200,
    val headers: Map<String, String> = mapOf(),
    // the weird name is required so Jackson serialises it into the JSON expected by API Gateway
    val isIsBase64Encoded: Boolean = false,
    val body: Any? = null
) {
    companion object {

        /** Factory function that creates a response with the error message and content type `text/plain`. */
        fun error(httpStatus: Int, errorMessage: String?): ProxyResponse =
            ProxyResponse(
                statusCode = httpStatus,
                body = errorMessage,
                headers = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.TEXT_PLAIN))
    }
}

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

    private val objectMapper = ObjectMapper()
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

    fun handle(proxyRequest: ProxyRequest): ProxyResponse = try {
        val request = proxyRequest.buildRequest()
        val handler = routeMap[Pair(request.method, request.path)] ?: throw DataNotFoundException()
        val response = handler.invoke(components, request)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE] ?: ContentTypes.APPLICATION_JSON
        val (encodedBody, isBase64Encoded) = encodeResponseBody(response.body, contentType, objectMapper)
        ProxyResponse(response.httpStatus, response.headers, isBase64Encoded, encodedBody)
    } catch (e: HttpException) {
        ProxyResponse.error(e.httpStatus, e.message)
    } catch (e: JsonProcessingException) {
        ProxyResponse.error(400, "Failed to parse JSON: ${e.message}")
    } catch (e: IllegalArgumentException) {
        ProxyResponse.error(400, e.message)
    } catch (e: Exception) {
        ProxyResponse.error(500, "Server Error")
    }

    companion object {
        val handlerMethod = "io.github.cjkent.osiris.aws.ProxyLambda::handle"
    }
}
