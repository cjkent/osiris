package io.github.cjkent.osiris.server

import io.github.cjkent.osiris.api.Headers
import io.github.cjkent.osiris.api.HttpHeaders
import io.github.cjkent.osiris.api.TestClient
import io.github.cjkent.osiris.api.TestResponse
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

typealias OkHttpResponse = okhttp3.Response

/**
 * The protocol used when making a request.
 */
enum class Protocol(val protocolName: String) {
    HTTP("http"),
    HTTPS("https")
}

/**
 * A very simple implementation of [TestClient] that makes HTTP or HTTPS requests.
 */
class TestHttpClient(
    val protocol: Protocol,
    val server: String,
    val port: Int,
    val basePath: String = ""
) : TestClient {

    private val client = OkHttpClient()

    override fun get(path: String, headers: Map<String, String>): TestResponse {
        val request = Request.Builder().url(buildPath(path)).build()
        val response = client.newCall(request).execute()
        return TestResponse(response.code(), response.headerMap(), response.body()?.string())
    }

    override fun post(path: String, body: String, headers: Map<String, String>): TestResponse {
        val mediaType = headers[HttpHeaders.CONTENT_TYPE]?.let { MediaType.parse(it) }
        val requestBody = RequestBody.create(mediaType, body)
        val request = Request.Builder().url(buildPath(path)).post(requestBody).build()
        val response = client.newCall(request).execute()
        return TestResponse(response.code(), response.headerMap(), response.body()?.string())
    }

    private fun buildPath(path: String): String = "${protocol.protocolName}://$server:$port$basePath$path"

    private fun OkHttpResponse.headerMap(): Headers =
        Headers(this.headers().toMultimap().mapValues { (_, list) -> list[0] })
}
