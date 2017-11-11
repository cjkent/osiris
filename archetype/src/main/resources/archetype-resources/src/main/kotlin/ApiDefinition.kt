package ${package}

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.cjkent.osiris.core.Auth
import io.github.cjkent.osiris.core.ContentTypes
import io.github.cjkent.osiris.core.DataNotFoundException
import io.github.cjkent.osiris.core.ForbiddenException
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.HttpHeaders
import io.github.cjkent.osiris.core.api
import io.github.cjkent.osiris.localserver.runLocalServer

/**
 * The name of an environment variable used to pass configuration to the code that handles the HTTP requests.
 *
 * The values of environment variables can be specified in the `environmentVariables` configuration element in the
 * Maven plugin. For example:
 *
 *     <environmentVariables>
 *         <EXAMPLE_ENVIRONMENT_VARIABLE>Example value</EXAMPLE_ENVIRONMENT_VARIABLE>
 *     </environmentVariables>
 */
const val EXAMPLE_ENVIRONMENT_VARIABLE = "EXAMPLE_ENVIRONMENT_VARIABLE"

/** The API. */
val api = api(ExampleComponents::class) {
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
        get("/env") { req ->
            // use the name property from ExampleComponents for the name
            JsonMessage("hello, $name!")
        }
    }
    post("/foo") { req ->
        // expecting a JSON payload like {"name":"Bob"}. use the ObjectMapper from ExampleComponents to deserialize
        val payload = objectMapper.readValue<JsonPayload>(req.requireBody(String::class))
        // this will be automatically converted to a JSON object like {"message":"hello, Bob!"}
        JsonMessage("hello, ${payload.name}!")
    }
    // require authorisation for all endpoints inside the auth block
    auth(Auth.AwsIam) {
        // this will be inaccessible unless a policy is created and attached to the calling user, role or group
        get("/topsecret") { req ->
            JsonMessage("For your eyes only")
        }
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
    get("/forbidden") { req ->
        throw ForbiddenException("top secret")
    }
    // Status 500 (server error). Returned when there is not specific handler for the exception type
    get("/servererror") { req ->
        throw RuntimeException("oh no!")
    }
}

/**
 * Creates the components used by the test API.
 */
fun createComponents(): ExampleComponents = ExampleComponentsImpl()

/**
 * A trivial set of components that exposes a simple property to the request handling code in the API definition and
 * an `ObjectMapper` for deserialising JSON.
 */
interface ExampleComponents : ComponentsProvider {
    val name: String
    val objectMapper: ObjectMapper
}

/**
 * An implementation of `ExampleComponents` that uses an environment variable to populate its data.
 */
class ExampleComponentsImpl : ExampleComponents {
    override val name: String = System.getenv(EXAMPLE_ENVIRONMENT_VARIABLE) ?:
        "[Environment variable EXAMPLE_ENVIRONMENT_VARIABLE not set]"
    override val objectMapper: ObjectMapper = jacksonObjectMapper()
}

/**
 * Simple class demonstrating creating an object from JSON in the request body.
 *
 * Expects JSON like:
 *
 *     {"name":"Bob"}
 */
data class JsonPayload(val name: String)

/**
 * Simple class demonstrating automatic conversion to JSON.
 *
 * Produces JSON like:
 *
 *     {"message":"hello, world!"}
 */
data class JsonMessage(val message: String)

/**
 * Starts a local server that exposes the example API on port 8080.
 */
fun main(args: Array<String>) {
    runLocalServer(api, createComponents())
}
