package io.github.cjkent.osiris.localserver

import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ApiBuilder
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.StandardFilters
import io.github.cjkent.osiris.core.TestClient
import io.github.cjkent.osiris.core.api
import io.github.cjkent.osiris.server.Protocol
import io.github.cjkent.osiris.server.TestHttpClient
import org.eclipse.jetty.server.Server

class LocalHttpTestClient private constructor(
    private val httpClient: TestClient,
    private val server: Server
) : TestClient by httpClient, AutoCloseable {

    override fun close() {
        server.stop()
    }

    companion object {

        // TODO randomise the port, retry a few times if the server doesn't start

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(api: Api<ComponentsProvider>): LocalHttpTestClient {
            val port = 8080
            val server = createLocalServer(api, object : ComponentsProvider {})
            val client = TestHttpClient(Protocol.HTTP, "localhost", port)
            server.start()
            return LocalHttpTestClient(client, server)
        }

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun create(body: ApiBuilder<ComponentsProvider>.() -> Unit): LocalHttpTestClient {
            val port = 8080
            val api = api(ComponentsProvider::class, StandardFilters.create(), body)
            val server = createLocalServer(api, object : ComponentsProvider {})
            val client = TestHttpClient(Protocol.HTTP, "localhost", port)
            server.start()
            return LocalHttpTestClient(client, server)
        }

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ComponentsProvider> create(components: T, api: Api<T>): LocalHttpTestClient {
            val port = 8080
            val server = createLocalServer(api, components)
            val client = TestHttpClient(Protocol.HTTP, "localhost", port)
            server.start()
            return LocalHttpTestClient(client, server)
        }

        /** Returns a client for a simple API that doesn't use any components in its handlers. */
        fun <T : ComponentsProvider> create(components: T, body: ApiBuilder<T>.() -> Unit): LocalHttpTestClient {
            val port = 8080
            val componentsType = components.javaClass.kotlin
            val api = api(componentsType, StandardFilters.create(), body)
            val server = createLocalServer(api, components)
            val client = TestHttpClient(Protocol.HTTP, "localhost", port)
            server.start()
            return LocalHttpTestClient(client, server)
        }
    }
}
