package io.github.cjkent.osiris.core

import java.nio.file.Files
import java.nio.file.Path

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
class InMemoryTestClient<T : ComponentsProvider> private constructor(
    api: Api<T>,
    private val components: T,
    private val staticFileDirectory: Path? = null,
    private val requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
) : TestClient {

    private val root: RouteNode<T> = RouteNode.create(api)

    override fun get(path: String, headers: Map<String, String>): TestResponse {
        val splitPath = path.split('?')
        val queryParams = when (splitPath.size) {
            1 -> Params()
            2 -> Params.fromQueryString(splitPath[1])
            else -> throw IllegalArgumentException("Unexpected path format - found two question marks")
        }
        val resource = splitPath[0]
        val routeMatch = root.match(HttpMethod.GET, resource)
        return if (routeMatch == null) {
            // if there's no match, check if it's a static path and serve the static file
            handleStaticRequest(resource)
        } else {
            handleRestRequest(resource, headers, queryParams, routeMatch)
        }
    }

    override fun post(path: String, body: String, headers: Map<String, String>): TestResponse {
        val headerParams = Params(headers)
        val (handler, vars) = root.match(HttpMethod.POST, path) ?: throw DataNotFoundException()
        val pathParams = Params(vars)
        val context = requestContextFactory.createContext(HttpMethod.POST, path, headerParams, Params(), pathParams, body)
        val request = Request(
            method = HttpMethod.POST,
            path = path,
            headers = headerParams,
            queryParams = Params(),
            pathParams = pathParams,
            context = context,
            body = body
        )
        val (status, responseHeaders, responseBody) = handler(components, request)
        val encodedBody = when (responseBody) {
            is EncodedBody -> responseBody.body
            else -> responseBody
        }
        return TestResponse(status, responseHeaders, encodedBody)
    }

    private fun handleRestRequest(
        resource: String,
        headers: Map<String, String>,
        queryParams: Params,
        routeMatch: RouteMatch<T>
    ): TestResponse {

        val headerParams = Params(headers)
        val pathParams = Params(routeMatch.vars)
        val context = requestContextFactory.createContext(HttpMethod.POST, resource, headerParams, Params(), pathParams, null)
        val request = Request(
            method = HttpMethod.GET,
            path = resource,
            headers = Params(headers),
            queryParams = queryParams,
            pathParams = pathParams,
            context = context
        )
        val (status, responseHeaders, body) = routeMatch.handler(components, request)
        val encodedBody = when (body) {
            is EncodedBody -> body.body
            else -> body
        }
        return TestResponse(status, responseHeaders, encodedBody)
    }

    private fun handleStaticRequest(resource: String): TestResponse {

        fun staticMatch(node: RouteNode<T>, requestPath: RequestPath): StaticRouteMatch? = when {
            node is StaticRouteNode<T> -> StaticRouteMatch(node, requestPath.segments.joinToString("/"))
            requestPath.isEmpty() -> null
            else -> node.fixedChildren[requestPath.head()]?.let { staticMatch(it, requestPath.tail()) }
        }

        val staticMatch = staticMatch(root, RequestPath(resource)) ?: throw DataNotFoundException()
        if (staticFileDirectory == null) {
            throw IllegalStateException("Received request for static resource " + "$resource but " +
                "no static directory is configured")
        }
        val file = if (staticMatch.path.isEmpty()) {
            // path points directly to the static endpoint in which case use the index file if there is one
            val indexFile = staticMatch.node.indexFile ?: throw DataNotFoundException()
            staticFileDirectory.resolve(indexFile)
        } else {
            staticFileDirectory.resolve(staticMatch.path)
        }
        val fileBytes = Files.readAllBytes(file)
        return TestResponse(200, Headers(), String(fileBytes, Charsets.UTF_8))
    }

    companion object {

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(
            api: Api<ComponentsProvider>,
            staticFilesDir: Path? = null,
            requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
        ): InMemoryTestClient<ComponentsProvider> =
            InMemoryTestClient(api, object : ComponentsProvider {}, staticFilesDir, requestContextFactory)

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ComponentsProvider> create(
            components: T,
            api: Api<T>,
            staticFilesDir: Path? = null,
            requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
        ): InMemoryTestClient<T> = InMemoryTestClient(api, components, staticFilesDir, requestContextFactory)
    }

    private inner class StaticRouteMatch(val node: StaticRouteNode<T>, val path: String)
}
