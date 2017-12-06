package io.github.cjkent.osiris.localserver

import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.RequestContextFactory
import io.github.cjkent.osiris.core.TestClient
import io.github.cjkent.osiris.server.HttpTestClient
import io.github.cjkent.osiris.server.Protocol
import org.eclipse.jetty.server.Server

/**
 * Test client that executes HTTP requests against an API hosted by an in-process Jetty server.
 */
class LocalHttpTestClient private constructor(
    private val httpClient: TestClient,
    private val server: Server
) : TestClient by httpClient, AutoCloseable {

    override fun close() {
        server.stop()
    }

    companion object {

        // TODO randomise the port, retry a few times if the server doesn't start

        /** Returns a client for an API that uses components in its handlers. */
        fun <T : ComponentsProvider> create(
            components: T,
            api: Api<T>,
            staticFilesDir: String? = null,
            requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
        ): LocalHttpTestClient {

            val port = 8080
            val server = createLocalServer(
                api,
                components,
                staticFilesDir = staticFilesDir,
                requestContextFactory = requestContextFactory)
            val client = HttpTestClient(Protocol.HTTP, "localhost", port)
            server.start()
            return LocalHttpTestClient(client, server)
        }
    }
}
