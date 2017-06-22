package io.github.cjkent.osiris.api

/**
 * A simple client for testing APIs.
 *
 * This can be implemented to hit the local server, an API deployed to API Gateway or a test server
 * running in memory.
 */
interface TestClient {
    fun get(path: String, headers: Map<String, String> = mapOf()): TestResponse
    // TODO maybe make the body a class (RequestBody?) that can contain the contents and the base64 flag
    fun post(path: String, body: String, headers: Map<String, String> = mapOf()): TestResponse

}

data class TestResponse(val status: Int, val headers: Headers, val body: Any?)

/**
 * Test client that dispatches requests to an API in memory without going via HTTP.
 */
class InMemoryTestClient<T : ComponentsProvider> private constructor(api: Api<T>, private val components: T) : TestClient {

    private val root: RouteNode<T> = RouteNode.create(api)

    override fun get(path: String, headers: Map<String, String>): TestResponse {
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
        val (status, responseHeaders, body) = handler(components, request)
        val encodedBody = when (body) {
            is EncodedBody -> body.body
            else -> body
        }
        return TestResponse(status, responseHeaders, encodedBody)
    }

    override fun post(path: String, body: String, headers: Map<String, String>): TestResponse {
        val (handler, vars) = root.match(HttpMethod.POST, path) ?: throw DataNotFoundException()
        val request = Request(
            method = HttpMethod.POST,
            path = path,
            headers = Params(headers),
            pathParams = Params(vars),
            queryParams = Params(),
            body = body
        )
        val (status, responseHeaders, responseBody) = handler(components, request)
        val encodedBody = when (responseBody) {
            is EncodedBody -> responseBody.body
            else -> responseBody
        }
        return TestResponse(status, responseHeaders, encodedBody)
    }

    companion object {

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(api: Api<ComponentsProvider>): InMemoryTestClient<ComponentsProvider> =
            InMemoryTestClient(api, object : ComponentsProvider {})

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(body: ApiBuilder<ComponentsProvider>.() -> Unit): InMemoryTestClient<ComponentsProvider> {
            val api = api(ComponentsProvider::class, StandardFilters.create(), body)
            return InMemoryTestClient(api, object : ComponentsProvider {})
        }

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ComponentsProvider> create(components: T, api: Api<T>): InMemoryTestClient<T> =
            InMemoryTestClient(api, components)

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun <T : ComponentsProvider> create(components: T, body: ApiBuilder<T>.() -> Unit): InMemoryTestClient<T> {
            val componentsType = components.javaClass.kotlin
            return InMemoryTestClient(api(componentsType, StandardFilters.create(), body), components)
        }
    }
}
