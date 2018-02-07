package io.github.cjkent.osiris.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.cjkent.osiris.core.ContentType
import io.github.cjkent.osiris.core.HttpHeaders
import io.github.cjkent.osiris.core.InMemoryTestClient
import io.github.cjkent.osiris.core.JSON_CONTENT_TYPE
import io.github.cjkent.osiris.core.MimeTypes
import io.github.cjkent.osiris.core.TestClient
import io.github.cjkent.osiris.localserver.LocalHttpTestClient
import org.testng.annotations.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val components: TestComponents = TestComponentsImpl("Bob", 42)

private const val STATIC_DIR = "src/test/static"

@Test
class InMemoryIntegrationTest {

    fun testApiInMemory() {
        val client = InMemoryTestClient.create(components, api, Paths.get(STATIC_DIR))
        assertApi(client)
    }
}

@Test
class LocalHttpIntegrationTest {

    fun testApiLocalHttpServer() {
        LocalHttpTestClient.create(components, api, STATIC_DIR).use { assertApi(it) }
    }
}

internal fun assertApi(client: TestClient) {
    val objectMapper = jacksonObjectMapper()
    fun Any?.parseJson(): Map<*, *> {
        val json = this as? String ?: throw IllegalArgumentException("Value is not a string: $this")
        return objectMapper.readValue(json, Map::class.java)
    }
    val rootResponse = client.get("/")
    assertEquals(mapOf("message" to "hello, root!"), rootResponse.body.parseJson())
    assertEquals(JSON_CONTENT_TYPE, ContentType.parse(rootResponse.headers[HttpHeaders.CONTENT_TYPE]!!))
    assertEquals(200, rootResponse.status)

    val response1 = client.get("/helloworld")
    assertEquals(mapOf("message" to "hello, world!"), response1.body.parseJson())
    assertEquals(JSON_CONTENT_TYPE, ContentType.parse(rootResponse.headers[HttpHeaders.CONTENT_TYPE]!!))
    assertEquals(200, response1.status)

    val response2 = client.get("/helloplain")
    assertEquals("hello, world!", response2.body)
    assertEquals(MimeTypes.TEXT_PLAIN, response2.headers[HttpHeaders.CONTENT_TYPE])

    assertEquals(mapOf("message" to "hello, world!"), client.get("/hello/queryparam1").body.parseJson())
    assertEquals(mapOf("message" to "hello, Alice!"), client.get("/hello/queryparam1?name=Alice").body.parseJson())
    assertEquals(mapOf("message" to "hello, Tom!"), client.get("/hello/queryparam2?name=Tom").body.parseJson())
    assertEquals(mapOf("message" to "hello, Peter!"), client.get("/hello/Peter").body.parseJson())
    assertEquals(mapOf("message" to "hello, Bob!"), client.get("/hello/env").body.parseJson())
    assertEquals(mapOf("message" to "hello, Max!"), client.post("/foo", "{\"name\":\"Max\"}").body.parseJson())

    val response3 = client.get("/hello/queryparam2")
    assertEquals(400, response3.status)
    assertEquals("No value named 'name'", response3.body)

    assertEquals(mapOf("message" to "foo 123 found"), client.get("/foo/123").body.parseJson())
    val response5 = client.get("/foo/234")
    assertEquals(404, response5.status)
    assertEquals("No foo found with ID 234", response5.body)

    val response6 = client.get("/servererror")
    assertEquals(500, response6.status)
    assertEquals("Server Error", response6.body)

    val response7 = client.get("/public")
    assertEquals(200, response7.status)
    val body7 = response7.body
    assertTrue(body7 is String && body7.contains("hello, world!"))

    val response8 = client.get("/public/")
    assertEquals(200, response8.status)
    val body8 = response8.body
    assertTrue(body8 is String && body8.contains("hello, world!"))

    val response9 = client.get("/public/index.html")
    assertEquals(200, response9.status)
    val body9 = response9.body
    assertTrue(body9 is String && body9.contains("hello, world!"))

    val response10 = client.get("/public/baz/bar.html")
    assertEquals(200, response10.status)
    val body10 = response10.body
    assertTrue(body10 is String && body10.contains("hello, bar!"))
}
