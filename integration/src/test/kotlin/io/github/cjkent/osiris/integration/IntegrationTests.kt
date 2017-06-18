package io.github.cjkent.osiris.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.ContentTypes
import io.github.cjkent.osiris.api.HttpHeaders
import io.github.cjkent.osiris.api.InMemoryTestClient
import io.github.cjkent.osiris.api.TestClient
import io.github.cjkent.osiris.api.api
import io.github.cjkent.osiris.localserver.LocalHttpTestClient
import org.testng.annotations.Test
import kotlin.test.assertEquals

private val objectMapper = jacksonObjectMapper()
private val components: TestComponents = TestComponentsImpl("Foo", 42)

private val api = api(TestComponents::class) {
    get("/helloworld") { _ ->
        // return a map that is automatically converted to JSON
        mapOf("message" to "hello, world!")
    }
    get("/helloplain") { req ->
        // return a response with customised headers
        req.responseBuilder()
            .header(HttpHeaders.CONTENT_TYPE, ContentTypes.TEXT_PLAIN)
            .build("hello, world!")
    }
    get("/hello/queryparam1") { req ->
        // get an optional query parameter
        val name = req.queryParams["name"] ?: "world"
        mapOf("message" to "hello, $name!")
    }
    get("/hello/queryparam2") { req ->
        // get a required query parameter
        val name = req.queryParams.required("name")
        mapOf("message" to "hello, $name!")
    }
    // use path() to group multiple endpoints under the same sub-path
    path("/hello") {
        get("/{name}") { req ->
            // get a path parameter
            val name = req.pathParams["name"]
            // this will be automatically converted to a JSON object like {"message":"hello, Bob!"}
            JsonMessage("hello, $name!")
        }
        get("/components") { _ ->
            // use the name property from TestComponents for the name
            JsonMessage("hello, $name!")
        }
    }
    post("/foo") { req ->
        // expecting a JSON payload like {"name":"Bob"}. use the ObjectMapper from ExampleComponents to deserialize
        val payload = objectMapper.readValue<JsonPayload>(req.requireBody())
        // this will be automatically converted to a JSON object like {"message":"hello, Bob!"}
        JsonMessage("hello, ${payload.name}!")
    }
    // TODO control the status
}

@Test
class InMemoryIntegrationTest {
    fun testApiInMemory() {
        val client = InMemoryTestClient.create(components, api)
        assertApi(client)
    }
}

@Test
class LocalHttpIntegrationTest {
    fun testApiLocalHttpServer() {
        LocalHttpTestClient.create(components, api).use { client ->
            assertApi(client)
        }
    }
}

//--------------------------------------------------------------------------------------------------

private interface TestComponents : ApiComponents {
    val name: String
    val size: Int
}

private class TestComponentsImpl(override val name: String, override val size: Int) : TestComponents

/**
 * Simple class demonstrating automatic conversion to JSON.
 *
 * Produces JSON like:
 *
 *     {"message":"hello, world!"}
 */
private data class JsonMessage(val message: String)

/**
 * Simple class demonstrating creating an object from JSON in the request body.
 *
 * Expects JSON like:
 *
 *     {"name":"Bob"}
 */
private data class JsonPayload(val name: String)

private fun assertApi(client: TestClient) {
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
    assertEquals(mapOf("message" to "hello, Foo!"), client.get("/hello/components").body.parseJson())
    assertEquals(mapOf("message" to "hello, Max!"), client.post("/foo", "{\"name\":\"Max\"}").body.parseJson())
}
