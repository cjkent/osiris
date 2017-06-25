package io.github.cjkent.osiris.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.cjkent.osiris.core.ContentTypes
import io.github.cjkent.osiris.core.HttpHeaders
import io.github.cjkent.osiris.core.InMemoryTestClient
import io.github.cjkent.osiris.core.TestClient
import io.github.cjkent.osiris.localserver.LocalHttpTestClient
import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val COMPONENTS: TestComponents = TestComponentsImpl("Bob", 42)
private val API = IntegrationTestApiDefinition().api

@Test
class InMemoryIntegrationTest {
    fun testApiInMemory() {
        val client = InMemoryTestClient.create(COMPONENTS, API)
        assertApi(client)
    }
}

@Test
class LocalHttpIntegrationTest {
    fun testApiLocalHttpServer() {
        LocalHttpTestClient.create(COMPONENTS, API).use { client ->
            assertApi(client)
        }
    }
}

internal fun assertApi(client: TestClient) {
    val objectMapper = jacksonObjectMapper()
    fun Any?.parseJson(): Map<*, *> {
        val json = this as? String ?: throw IllegalArgumentException("Value is not a string: $this")
        return objectMapper.readValue(json, Map::class.java)
    }
    val response1 = client.get("/helloworld")
    assertEquals(mapOf("message" to "hello, world!"), response1.body.parseJson())
    assertEquals(ContentTypes.APPLICATION_JSON, response1.headers[HttpHeaders.CONTENT_TYPE])
    assertEquals(200, response1.status)

    val response2 = client.get("/helloplain")
    assertEquals("hello, world!", response2.body)
    assertEquals(ContentTypes.TEXT_PLAIN, response2.headers[HttpHeaders.CONTENT_TYPE])

    assertEquals(mapOf("message" to "hello, world!"), client.get("/hello/queryparam1").body.parseJson())
    assertEquals(mapOf("message" to "hello, Alice!"), client.get("/hello/queryparam1?name=Alice").body.parseJson())
    assertEquals(mapOf("message" to "hello, Tom!"), client.get("/hello/queryparam2?name=Tom").body.parseJson())
    assertEquals(mapOf("message" to "hello, Peter!"), client.get("/hello/Peter").body.parseJson())
    assertEquals(mapOf("message" to "hello, Bob!"), client.get("/hello/env").body.parseJson())
    assertEquals(mapOf("message" to "hello, Max!"), client.post("/foo", "{\"name\":\"Max\"}").body.parseJson())

    val response3 = client.get("/hello/queryparam2")
    assertEquals(400, response3.status)
    assertEquals("No value named 'name'", response3.body)

    val response4 = client.post("/foo", "{\"name\":\"this is malformed JSON\"")
    assertEquals(400, response4.status)
    assertTrue((response4.body as String).startsWith("Failed to parse JSON"))

    assertEquals(mapOf("message" to "foo 123 found"), client.get("/foo/123").body.parseJson())
    val response5 = client.get("/foo/234")
    assertEquals(404, response5.status)
    assertEquals("No foo found with ID 234", response5.body)

    val response6 = client.get("/servererror")
    assertEquals(500, response6.status)
    assertEquals("Server Error", response6.body)
}
