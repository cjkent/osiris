package io.github.cjkent.osiris.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.cjkent.osiris.api.ApiDefinition
import io.github.cjkent.osiris.api.ComponentsProvider
import io.github.cjkent.osiris.api.ContentTypes
import io.github.cjkent.osiris.api.DataNotFoundException
import io.github.cjkent.osiris.api.ForbiddenException
import io.github.cjkent.osiris.api.HttpHeaders
import io.github.cjkent.osiris.api.api

interface TestComponents : ComponentsProvider {
    val objectMapper: ObjectMapper
    val name: String
    val size: Int
}

class TestComponentsImpl(override val name: String, override val size: Int) : TestComponents {
    override val objectMapper: ObjectMapper = jacksonObjectMapper()
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
class IntegrationTestApiDefinition : ApiDefinition<TestComponents> {

    override val api = api(TestComponents::class) {
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
            get("/env") { _ ->
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
        // Endpoints demonstrating the mapping of exceptions to responses
        // Demonstrates mapping DataNotFoundException to a 404
        get("/foo/{fooId}") { req ->
            val fooId = req.pathParams.required("fooId")
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

}
