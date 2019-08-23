package ws.osiris.integration

import com.google.gson.Gson
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.DataNotFoundException
import ws.osiris.core.ForbiddenException
import ws.osiris.core.HttpHeaders
import ws.osiris.core.MimeTypes
import ws.osiris.core.api

interface TestComponents : ComponentsProvider {
    val gson: Gson
    val name: String
    val size: Int
}

class TestComponentsImpl(override val name: String, override val size: Int) : TestComponents {

    constructor() : this("Bob", 42)

    override val gson: Gson = Gson()
}

/**
 * Simple class demonstrating automatic conversion to JSON.
 *
 * Produces JSON like:
 *
 *     {"message":"hello, world!"}
 */
internal data class JsonMessage(val message: String)

/**
 * Simple class demonstrating creating an object from JSON in the request body.
 *
 * Expects JSON like:
 *
 *     {"name":"Bob"}
 */
internal data class JsonPayload(val name: String)

/**
 * An API definition that can be deployed to AWS and have integration tests run against it.
 */
val api = api<TestComponents> {

    staticFiles {
        path = "/public"
        indexFile = "index.html"
    }
    get("/") {
        mapOf("message" to "hello, root!")
    }
    get("/helloworld") {
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
        get("/env") {
            // use the name property from TestComponents for the name
            JsonMessage("hello, $name!")
        }
    }
    post("/foo") { req ->
        // expecting a JSON payload like {"name":"Bob"}
        val payload = gson.fromJson(req.requireBody(String::class), JsonPayload::class.java)
        // this will be automatically converted to a JSON object like {"message":"hello, Bob!"}
        JsonMessage("hello, ${payload.name}!")
    }
    // Endpoints demonstrating the mapping of exceptions to responses
    // Demonstrates mapping DataNotFoundException to a 404
    get("/foo/{fooId}") { req ->
        val fooId = req.pathParams["fooId"]
        when (fooId) {
            "123" -> JsonMessage("foo 123 found")
            else -> throw DataNotFoundException("No foo found with ID $fooId")
        }
    }
    // Status 403 (forbidden)
    get("/forbidden") {
        throw ForbiddenException("top secret")
    }
    // Status 500 (server error). Returned when there is not specific handler for the exception type
    get("/servererror") {
        throw RuntimeException("oh no!")
    }
}

/**
 * Creates the components used by the test API.
 */
fun createComponents(): TestComponents = TestComponentsImpl()
