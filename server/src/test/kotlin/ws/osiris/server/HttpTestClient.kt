package ws.osiris.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import ws.osiris.core.Headers
import ws.osiris.core.HttpHeaders
import ws.osiris.core.TestClient
import ws.osiris.core.TestResponse

typealias OkHttpResponse = okhttp3.Response

/**
 * The protocol used when making a request.
 */
enum class Protocol(val protocolName: String, val defaultPort: Int) {
    HTTP("http", 80),
    HTTPS("https", 443)
}

/**
 * A very simple implementation of [TestClient] that makes HTTP or HTTPS requests.
 */
class HttpTestClient(
    private val protocol: Protocol,
    private val server: String,
    private val port: Int = protocol.defaultPort,
    private val basePath: String = ""
) : TestClient {

    private val client = OkHttpClient()

    override fun get(path: String, headers: Map<String, String>): TestResponse {
        val request = Request.Builder().url(buildPath(path)).build()
        log.debug("Making request {}", request)
        val response = client.newCall(request).execute()
        val testResponse = TestResponse(response.code, response.headerMap(), response.body?.string())
        log.debug("Received response {}", testResponse)
        return testResponse
    }

    override fun post(path: String, body: String, headers: Map<String, String>): TestResponse {
        val mediaType = headers[HttpHeaders.CONTENT_TYPE]?.toMediaType()
        val requestBody = body.toRequestBody(mediaType)
        val request = Request.Builder().url(buildPath(path)).post(requestBody).build()
        log.debug("Making request {}", request)
        val response = client.newCall(request).execute()
        val testResponse = TestResponse(response.code, response.headerMap(), response.body?.string())
        log.debug("Received response {}", testResponse)
        return testResponse
    }

    override fun options(path: String, headers: Map<String, String>): TestResponse {
        val request = Request.Builder().url(buildPath(path)).method("OPTIONS", null).build()
        log.debug("Making request {}", request)
        val response = client.newCall(request).execute()
        val testResponse = TestResponse(response.code, response.headerMap(), null)
        log.debug("Received response {}", testResponse)
        return testResponse
    }

    private fun buildPath(path: String): String = "${protocol.protocolName}://$server:$port$basePath$path"

    private fun OkHttpResponse.headerMap(): Headers =
        Headers(this.headers.toMultimap().mapValues { (_, list) -> list[0] })

    companion object {
        private val log = LoggerFactory.getLogger(HttpTestClient::class.java)
    }
}
