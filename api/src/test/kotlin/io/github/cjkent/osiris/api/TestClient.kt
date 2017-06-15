package io.github.cjkent.osiris.api

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

    override fun get(path: String, headers: Map<String, String>): Response {
        val (handler, vars) = root.match(HttpMethod.GET, path) ?: throw DataNotFoundException()
        val splitPath = path.split('?')
        val queryParams = when (splitPath.size) {
            1 -> Params()
            2 -> Params.fromQueryString(splitPath[1])
            else -> throw IllegalArgumentException("Unexpected path format - found two question marks")
        }
        val request = Request(
            method = HttpMethod.GET,
            path = path,
            headers = Params(headers),
            pathParams = Params(vars),
            queryParams = queryParams
        )
        return handler(components, request)
    }

    override fun post(path: String, body: String, headers: Map<String, String>): Response {
        val (handler, vars) = root.match(HttpMethod.GET, path) ?: throw DataNotFoundException()
        val request = Request(
            method = HttpMethod.POST,
            path = path,
            headers = Params(headers),
            pathParams = Params(vars),
            queryParams = Params(),
            body = body
        )
        return handler(components, request)
    }

    companion object {

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(api: Api<ApiComponents>): InMemoryTestClient<ApiComponents> =
            InMemoryTestClient(api, object : ApiComponents {})

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ApiComponents> create(api: Api<T>, components: T): InMemoryTestClient<T> =
            InMemoryTestClient(api, components)
    }
}
