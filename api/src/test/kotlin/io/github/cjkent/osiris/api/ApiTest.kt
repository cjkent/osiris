package io.github.cjkent.osiris.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Test
class ApiTest {

    fun pathPattern() {
        Route.validatePath("/")
        Route.validatePath("/foo")
        Route.validatePath("/{foo}")
        Route.validatePath("/123~_-.()")
        Route.validatePath("/foo/bar")
        Route.validatePath("/foo/{bar}")
        Route.validatePath("/foo/{bar}/baz")
        assertFailsWith<IllegalArgumentException> { Route.validatePath("foo") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/foo/") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/fo{o") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/fo{o}") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/{fo{o}") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/{fo}o}") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/{fo}o") }
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

    //--------------------------------------------------------------------------------------------------

    /**
     * Tests creating a very simple API using the DSL, a components interface and components implementation.
     *
     * This is mostly to make sure I don't break the type signatures if I'm mucking around with the generics.
     */
    fun createApi() {
        val api = api(TestComponentsImpl::class) {
            get("/name") {
                name
            }
            get("/size") {
                size
            }
        }
        assertEquals(2, api.routes.size)
        assertEquals("/name", api.routes[0].path)
        assertEquals("/size", api.routes[1].path)
    }

    // TODO factor this out so the client is passed in - can use the same assertions for in-memory, local and AWS APIs
    // local http client can be auto-closable and stop the server in close()
    /**
     * Tests the basic features of an API
     */
    fun basicFeatures() {
        val components = TestComponentsImpl("Foo", 42)
        val objectMapper = jacksonObjectMapper()
        fun Any?.parseJson(): Map<*, *> {
            val json = this as? String ?: throw IllegalArgumentException("Value is not a string: $this")
            return objectMapper.readValue(json, Map::class.java)
        }
        val client = InMemoryTestClient.create(components) {
            get("/helloworld") { req ->
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
                get("/components") { req ->
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
}
