package ws.osiris.localserver

import org.eclipse.jetty.server.Server
import ws.osiris.core.Api
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.RequestContextFactory
import ws.osiris.core.TestClient
import ws.osiris.server.HttpTestClient
import ws.osiris.server.Protocol

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
            api: Api<T>,
            components: T,
            staticFilesDir: String? = null,
            requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
        ): LocalHttpTestClient {

            val port = 8080
            val server = createLocalServer(
                api,
                components,
                staticFilesDir = staticFilesDir,
                requestContextFactory = requestContextFactory
            )
            val client = HttpTestClient(Protocol.HTTP, "localhost", port)
            server.start()
            return LocalHttpTestClient(client, server)
        }
    }
}
