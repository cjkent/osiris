package ${package}.core

import ws.osiris.core.MimeTypes
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.HttpHeaders
import ws.osiris.core.api

/**
 * The name of an environment variable used to pass in configuration.
 *
 * The values of environment variables can be specified by the `environmentVariables` property in the configuration:
 *
 *     val config = ApplicationConfig(
 *         environmentVariables = mapOf(
 *             "EXAMPLE_ENVIRONMENT_VARIABLE" to "Bob"
 *         )
 *         ...
 *     )
 */
const val EXAMPLE_ENVIRONMENT_VARIABLE = "EXAMPLE_ENVIRONMENT_VARIABLE"

/** The API. */
val api = api<ExampleComponents> {

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

    get("/helloqueryparam") { req ->
        // get a query parameter
        val name = req.queryParams["name"]
        mapOf("message" to "hello, $name!")
    }

    get("/helloenv") { _ ->
        // use the name property from ExampleComponents for the name
        mapOf("message" to "hello, $name!")
    }

    get("/hello/{name}") { req ->
        // get a path parameter
        val name = req.pathParams["name"]
        mapOf("message" to "hello, $name!")
    }
}

/**
 * Creates the components used by the example API.
 */
fun createComponents(): ExampleComponents = ExampleComponentsImpl()

/**
 * A trivial set of components that exposes a simple property to the request handling code in the API definition.
 */
interface ExampleComponents : ComponentsProvider {
    val name: String
}

/**
 * An implementation of `ExampleComponents` that uses an environment variable to populate its data.
 */
class ExampleComponentsImpl : ExampleComponents {
    override val name: String = System.getenv(EXAMPLE_ENVIRONMENT_VARIABLE) ?:
        "[Environment variable EXAMPLE_ENVIRONMENT_VARIABLE not set]"
}
