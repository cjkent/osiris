package io.github.cjkent.osiris.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/**
 * A simple client for testing APIs.
 *
 * This can be implemented to hit the local server, an API deployed to API Gateway or a test server
 * running in memory.
 */
interface TestClient {
    fun get(path: String, headers: Map<String, String> = mapOf()): Response
    // TODO maybe make the body a class (RequestBody?) that can contain the contents and the base64 flag
    fun post(path: String, body: String, headers: Map<String, String> = mapOf()): Response
}

/**
 * Test client that dispatches requests to an API in memory without going via HTTP.
 */
class InMemoryTestClient<T : ApiComponents> private constructor(api: Api<T>, private val components: T) : TestClient {

    private val root = RouteNode.create(api)
    private val objectMapper = jacksonObjectMapper()

    override fun get(path: String, headers: Map<String, String>): Response {
        val splitPath = path.split('?')
        val queryParams = when (splitPath.size) {
            1 -> Params()
            2 -> Params.fromQueryString(splitPath[1])
            else -> throw IllegalArgumentException("Unexpected path format - found two question marks")
        }
        val resource = splitPath[0]
        val (handler, vars) = root.match(HttpMethod.GET, resource) ?: throw DataNotFoundException()
        val request = Request(
            method = HttpMethod.GET,
            path = resource,
            headers = Params(headers),
            pathParams = Params(vars),
            queryParams = queryParams
        )
        val response = handler(components, request)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE]
        val encodedBody = encodeResponseBody(response.body, contentType)
        return response.copy(body = encodedBody)
    }

    override fun post(path: String, body: String, headers: Map<String, String>): Response {
        val (handler, vars) = root.match(HttpMethod.POST, path) ?: throw DataNotFoundException()
        val request = Request(
            method = HttpMethod.POST,
            path = path,
            headers = Params(headers),
            pathParams = Params(vars),
            queryParams = Params(),
            body = body
        )
        val response = handler(components, request)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE]
        val encodedBody = encodeResponseBody(response.body, contentType)
        return response.copy(body = encodedBody)
    }

    /**
     * Encodes the response received from the request handler code into a string that can be serialised into
     * the response body.
     *
     * Handling of body types by content type:
     *   * content type = JSON
     *     * null - no body
     *     * string - assumed to be JSON, used as-is, no base64
     *     * ByteArray - base64 encoded
     *     * object - converted to a JSON string using Jackson
     *   * content type != JSON
     *     * null - no body
     *     * string - used as-is, no base64 - Jackson should handle escaping when AWS does the conversion
     *     * ByteArray - base64 encoded
     *     * any other type throws an exception
     */
    private fun encodeResponseBody(body: Any?, contentType: String?): String? =
        if (contentType == ContentTypes.APPLICATION_JSON) {
            when (body) {
                null, is String -> body as String?
                is ByteArray -> String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8)
                else -> objectMapper.writeValueAsString(body)
            }
        } else {
            when (body) {
                null, is String -> body as String?
                is ByteArray -> String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8)
                else -> throw RuntimeException("Cannot convert value of type ${body.javaClass.name} to response body")
            }
        }

    companion object {

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(api: Api<ApiComponents>): InMemoryTestClient<ApiComponents> =
            InMemoryTestClient(api, object : ApiComponents {})

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(body: ApiBuilder<ApiComponents>.() -> Unit): InMemoryTestClient<ApiComponents> =
            InMemoryTestClient(api(ApiComponents::class, body), object : ApiComponents {})

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ApiComponents> create(components: T, api: Api<T>): InMemoryTestClient<T> =
            InMemoryTestClient(api, components)

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun <T : ApiComponents> create(components: T, body: ApiBuilder<T>.() -> Unit): InMemoryTestClient<T> =
            InMemoryTestClient(api(components.javaClass.kotlin, body), components)
    }
}
