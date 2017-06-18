package io.github.cjkent.osiris.localserver

import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiBuilder
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.Response
import io.github.cjkent.osiris.api.StandardFilters
import io.github.cjkent.osiris.api.TestClient
import io.github.cjkent.osiris.api.api
import org.eclipse.jetty.server.Server

/** Dummy [ApiComponents] implementation used when testing an API that doesn't use components. */
class TestApiComponents : ApiComponents

class LocalHttpTestClient private constructor(private val server: Server) : TestClient, AutoCloseable {

    override fun get(path: String, headers: Map<String, String>): Response {
        throw UnsupportedOperationException("get not implemented")
    }

    override fun post(path: String, body: String, headers: Map<String, String>): Response {
        throw UnsupportedOperationException("post not implemented")
    }

    override fun close() {
        server.stop()
    }

    companion object {

        // TODO randomise the port, retry a few times if the server doesn't start

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(api: Api<ApiComponents>): LocalHttpTestClient {
            val server = createLocalServer(api, object : ApiComponents {})
            return LocalHttpTestClient(server)
        }

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(body: ApiBuilder<ApiComponents>.() -> Unit): LocalHttpTestClient {
            val api = api(ApiComponents::class, StandardFilters.create(ApiComponents::class), body)
            val server = createLocalServer(api, object : ApiComponents {})
            return LocalHttpTestClient(server)
        }

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ApiComponents> create(components: T, api: Api<T>): LocalHttpTestClient {
            val server = createLocalServer(api, components)
            return LocalHttpTestClient(server)
        }

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun <T : ApiComponents> create(components: T, body: ApiBuilder<T>.() -> Unit): LocalHttpTestClient {
            val componentsType = components.javaClass.kotlin
            val api = api(componentsType, StandardFilters.create(componentsType), body)
            val server = createLocalServer(api, components)
            return LocalHttpTestClient(server)
        }
    }
}
