package io.github.cjkent.osiris.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    private interface TestComponents : ComponentsProvider {
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
        val api = api<TestComponentsImpl> {
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
        val components: TestComponents = TestComponentsImpl("Foo", 42)
        val objectMapper = jacksonObjectMapper()
        fun Any?.parseJson(): Map<*, *> {
            val json = this as? String ?: throw IllegalArgumentException("Value is not a string: $this")
            return objectMapper.readValue(json, Map::class.java)
        }

        val api = api<TestComponents> {
            get("/helloworld") { _ ->
                // return a map that is automatically converted to JSON
                mapOf("message" to "hello, world!")
            }
            get("/helloplain") { req ->
                // return a response with customised headers
                req.responseBuilder()
                    .header(HttpHeaders.CONTENT_TYPE, MimeTypes.TEXT_PLAIN)
                    .build("hello, world!")
            }
            get("/hello/queryparam1") { req ->
                // get an optional query parameter
                val name = req.queryParams.optional("name") ?: "world"
                mapOf("message" to "hello, $name!")
            }
            get("/hello/queryparam2") { req ->
                // get a required query parameter
                val name = req.queryParams["name"]
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
                val payload = objectMapper.readValue<JsonPayload>(req.requireBody(String::class))
                // this will be automatically converted to a JSON object like {"message":"hello, Bob!"}
                JsonMessage("hello, ${payload.name}!")
            }
            // TODO control the status
        }
        val client = InMemoryTestClient.create(components, api)

        val response1 = client.get("/helloworld")
        assertEquals(mapOf("message" to "hello, world!"), response1.body.parseJson())
        assertEquals(JSON_CONTENT_TYPE.header, response1.headers[HttpHeaders.CONTENT_TYPE])
        assertEquals(200, response1.status)

        val response2 = client.get("/helloplain")
        assertEquals("hello, world!", response2.body)
        assertEquals(MimeTypes.TEXT_PLAIN, response2.headers[HttpHeaders.CONTENT_TYPE])

        assertEquals(mapOf("message" to "hello, world!"), client.get("/hello/queryparam1").body.parseJson())
        assertEquals(mapOf("message" to "hello, Alice!"), client.get("/hello/queryparam1?name=Alice").body.parseJson())
        assertEquals(mapOf("message" to "hello, Tom!"), client.get("/hello/queryparam2?name=Tom").body.parseJson())
        assertEquals(mapOf("message" to "hello, Peter!"), client.get("/hello/Peter").body.parseJson())
        assertEquals(mapOf("message" to "hello, Foo!"), client.get("/hello/components").body.parseJson())
        assertEquals(mapOf("message" to "hello, Max!"), client.post("/foo", "{\"name\":\"Max\"}").body.parseJson())
    }

    // I suspect the current impl only gets the direct children of the root builder, not all descendants
    fun deeplyNestedBuilders() {
        val components: TestComponents = TestComponentsImpl("Foo", 42)
        val api = api<TestComponents> {
            // use path() to group multiple endpoints under the same sub-path
            path("/hello") {
                get("/level1") { _ ->
                    "level1"
                }
                path("/world") {
                    get("/level2") { _ ->
                        "level2"
                    }
                }
            }
        }
        val client = InMemoryTestClient.create(components, api)
        assertEquals("level1", client.get("/hello/level1").body.toString())
        assertEquals("level2", client.get("/hello/world/level2").body.toString())
    }

    fun staticFiles() {
        val api = api<ComponentsProvider> {

            staticFiles {
                path = "/static"
            }

            get("/foo") {
            }
        }
        val routeNode = RouteNode.create(api)
        assertTrue(api.staticFiles)
        // the static path shouldn't match because the request will be handled separately and won't even
        // be passed to the lambda.
        assertNull(routeNode.match(HttpMethod.GET, "/static"))
        assertNotNull(routeNode.match(HttpMethod.GET, "/foo"))
    }

    fun staticFilesClash() {
        val api = api<ComponentsProvider> {

            staticFiles {
                path = "/foo/bar"
            }

            get("/foo/bar") {
            }
        }
        assertFailsWith<IllegalArgumentException> { RouteNode.create(api) }
    }

    fun staticFilesOverlapsVariablePath() {
        val api = api<ComponentsProvider> {

            staticFiles {
                path = "/foo/bar"
            }

            get("/foo/{v}") {
            }
        }
        RouteNode.create(api)
    }

    fun staticFilesAuth() {
        val api = api<ComponentsProvider> {
            auth(TestAuth) {
                staticFiles {
                    path = "/static"
                }
            }
        }
        val staticRoute = api.routes.find { it is StaticRoute<*> } ?: fail()
        assertEquals("/static", staticRoute.path)
        assertEquals(TestAuth, staticRoute.auth)
    }

    fun staticFilesPathAndAuth() {
        val api = api<ComponentsProvider> {
            path("/base") {
                auth(TestAuth) {
                    staticFiles {
                        path = "/static"
                    }
                }
            }
        }
        val staticRoute = api.routes.find { it is StaticRoute<*> } ?: fail()
        assertEquals("/base/static", staticRoute.path)
        assertEquals(TestAuth, staticRoute.auth)
    }

    fun validateStaticFiles() {
        api<ComponentsProvider> { staticFiles { path = "/foo" } }
        api<ComponentsProvider> { staticFiles { path = "/foo/bar" } }
        api<ComponentsProvider> { staticFiles { path = "/" } }
        assertFailsWith<IllegalArgumentException> { api<ComponentsProvider> { staticFiles { path = "" } } }
        assertFailsWith<IllegalArgumentException> { api<ComponentsProvider> { staticFiles { path = "foo" } } }
        assertFailsWith<IllegalArgumentException> { api<ComponentsProvider> { staticFiles { path = "/foo bar" } } }
        assertFailsWith<IllegalArgumentException> { api<ComponentsProvider> { staticFiles { path = "/foo$" } } }
    }

    fun merge2Apis() {
        val api1 = api<ComponentsProvider> {
            get("/foo") {
                ""
            }
        }
        val api2 = api<ComponentsProvider> {
            get("/bar") {
                ""
            }
        }
        val api = Api.merge(api1, api2)
        assertEquals(2, api.routes.size)
        assertEquals("/foo", api.routes[0].path)
        assertEquals("/bar", api.routes[1].path)
    }

    fun merge4Apis() {
        val api1 = api<ComponentsProvider> {
            get("/foo") {
                ""
            }
        }
        val api2 = api<ComponentsProvider> {
            get("/bar") {
                ""
            }
        }
        val api3 = api<ComponentsProvider> {
            get("/baz") {
                ""
            }
        }
        val api4 = api<ComponentsProvider> {
            get("/qux") {
                ""
            }
        }
        val api = Api.merge(api1, api2, api3, api4)
        assertEquals(4, api.routes.size)
        assertEquals("/foo", api.routes[0].path)
        assertEquals("/bar", api.routes[1].path)
        assertEquals("/baz", api.routes[2].path)
        assertEquals("/qux", api.routes[3].path)

    }

    fun mergesApiWithDuplicateRoutes() {
        val api1 = api<ComponentsProvider> {
            get("/foo") {
                ""
            }
        }
        val api2 = api<ComponentsProvider> {
            get("/foo") {
                ""
            }
        }
        assertFailsWith<IllegalArgumentException> { RouteNode.create(Api.merge(api1, api2)) }
    }
}

object TestAuth : Auth {
    override val name: String = "test"
}
