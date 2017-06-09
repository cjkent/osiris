package io.github.cjkent.osiris.api

import java.net.URLDecoder
import kotlin.reflect.KClass

const val API_COMPONENTS_CLASS = "API_COMPONENTS_CLASS"
const val API_DEFINITION_CLASS = "API_DEFINITION_CLASS"

/**
 * A model describing an API; it contains the routes to the API endpoints and the code executed
 * when the API receives requests.
 */
data class Api<in T : ApiComponents>(
    /**
     * The routes defined by the API.
     *
     * Each route consists of:
     *   * An HTTP method
     *   * A path
     *   * The code that is executed when a request is received for the endpoint
     *   * The authorisation required to invoke the endpoint.
     */
    val routes: List<Route<T>>,
    /**
     * The type of the object available to the code in the API definition that handles the HTTP requests.
     *
     * The code in the API definition runs with the components class as the implicit receiver
     * and can directly access its properties and methods.
     *
     * For example, if a data store is needed to handle a request, it would be provided by
     * the `ApiComponents` implementation:
     *
     *     class Components(val dataStore: DataStore) : ApiComponents
     *
     *     ...
     *
     *     get("/orders/{orderId}") { req ->
     *         val orderId = req.pathParams["orderId"]
     *         dataStore.loadOrderDetails(orderId)
     *     }
     */
    val componentsClass: KClass<in T>)

/**
 * A set of HTTP parameters; can represent headers, path parameters or query string parameters.
 *
 * This does not support repeated values in query strings as API Gateway doesn't support them.
 * For example, a query string of `foo=123&foo=456` will only contain one value for `foo`. It is
 * undefined which one.
 */
class Params(params: Map<String, String>?) {

    val params: Map<String, String> = params ?: mapOf()

    /**
     * Returns the named parameter.
     *
     * If there are multiple values it is undefined which is returned.
     */
    operator fun get(name: String): String? = params[name]

    /**
     * Returns the named parameter or throws `IllegalArgumentException` if there is no parameter with the name.
     *
     * If there are multiple values it is undefined which is returned.
     */
    fun required(name: String): String = get(name) ?: throw IllegalArgumentException("No value named '$name'")

    companion object {

        /** Creates a set of parameters by parsing an HTTP query string. */
        fun fromQueryString(queryString: String?): Params {
            if (queryString == null || queryString.trim().isEmpty()) return Params(mapOf())
            val params = URLDecoder.decode(queryString, "UTF-8").split("&").map { splitVar(it) }.toMap()
            return Params(params)
        }

        private fun splitVar(nameAndValue: String): Pair<String, String> {
            val index = nameAndValue.indexOf('=')
            if (index == -1) return Pair(nameAndValue, "")
            return Pair(nameAndValue.substring(0, index), nameAndValue.substring(index + 1, nameAndValue.length))
        }
    }
}

/**
 * Contains the details of an HTTP request received by the API.
 */
class Request(
    val method: HttpMethod,
    val path: String,
    val headers: Params,
    val queryParams: Params,
    val pathParams: Params,
    // TODO body - this is a string for JSON & XML
    // it's also a string for zip and octet-stream with base64 encoding = false. not sure if I get that
    // if it's converting a binary body to a string, what charset is it using?

    // https://aws.amazon.com/blogs/compute/binary-support-for-api-integrations-with-amazon-api-gateway/
    // "In the case of Lambda Function and Lambda Function Proxy Integrations, which currently only support JSON,
    // the request body is always converted to JSON."
    // what does "converted to JSON" mean for a binary file? how can I get the binary back?
    val body: String?,
    val bodyIsBase64Encoded: Boolean
) {

    /** Returns the body or throws `IllegalArgumentException` if it is null. */
    fun requireBody(): String = body ?: throw IllegalArgumentException("Request body is required")

    // TODO populate the default header from some config
    /**
     * Returns a builder for building a response.
     *
     * This is used to customise the headers or the status of the response.
     */
    fun responseBuilder(): ResponseBuilder =
        ResponseBuilder(mutableMapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.APPLICATION_JSON))
}

/**
 * Standard HTTP header names.
 */
object HttpHeaders {
    const val CONTENT_TYPE = "Content-Type"
}

/**
 * Standard content types.
 */
object ContentTypes {
    const val APPLICATION_JSON = "application/json"
    const val TEXT_PLAIN = "text/plain"
}

/**
 * Builder for building custom responses.
 *
 * Response builders are used in cases where the response headers or status need to be changed from the defaults.
 * This happens when the response is a success but the status is not 200 (OK). For example, 201 (created) or
 * 202 (accepted).
 *
 * It is also necessary to use a builder to change any of the headers, for example if the content type
 * is not the default.
 */
class ResponseBuilder internal constructor(val headers: MutableMap<String, String>) {

    private var httpStatus: Int = 200

    /** Sets the value of the named header and returns this builder. */
    fun header(name: String, value: String): ResponseBuilder {
        headers[name] = value
        return this
    }

    /** Sets the status code of the response and returns this builder. */
    fun httpStatus(status: Int): ResponseBuilder {
        httpStatus = status
        return this
    }

    /** Builds a response from the data in this builder. */
    fun build(body: Any? = null): Response = Response(httpStatus, headers, body)
}

/**
 * The details of the HTTP response returned from the code handling a request.
 *
 * It is only necessary to return a `Response` when the headers or status need to be customised.
 * In many cases it is sufficient to return a value that is serialised into the response body
 * and has a status of 200 (OK).
 */
class Response(val httpStatus: Int, val headers: Map<String, String>, val body: Any?)

enum class HttpMethod {
    GET,
    POST,
    PUT,
    UPDATE,
    DELETE
}
typealias Handler<T> = T.(req: Request) -> Any

data class Route<in T : ApiComponents>(
    val method: HttpMethod,
    val path: String,
    val handler: Handler<T>,
    val auth: Auth? = null)

/**
 * Marks the DSL implicit receivers to avoid scoping problems.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class OsirisDsl

// TODO filters. are they essential? how would that affect the API? can I leave them for now and add them later?
// Is there a neat way to do it without mutating the request or response?
// If a filter wants to make a change to the response how is that visible to the next filter or the handler?
// Is the response from a filter used to initialise the ResponseBuilder in the next request?
// What if the filter wants to modify the request? return it?
// Return a FilterResponse that can contain a request and response?
// Could filters be like Ring middleware and invoke the next Handler? Would have to impl Handler
// Ring middleware fns capture the next handler which means they can have the same signature as a handler and
// don't need a handler parameter.
// Could wrap in a Handler that contains the next Handler and the filter.
// The wrapper could check whether the filter matches the request and invoke it or directly invoke the handler
// To start with all handlers could be at the end of a chain containing all filters.
// A more efficient impl would be to match the filter patterns to the route patterns and only chain together filters
// and handlers where it is possible they could match the same path
@OsirisDsl
class ApiBuilder<T : ApiComponents>(val componentsClass: KClass<T>, val prefix: String, val auth: Auth?) {

    constructor(componentsType: KClass<T>) : this(componentsType, "", null)

    private val routes = arrayListOf<Route<T>>()
    private val children = arrayListOf<ApiBuilder<T>>()

    fun get(path: String, handler: Handler<T>) = addRoute(HttpMethod.GET, path, handler)
    fun post(path: String, handler: Handler<T>) = addRoute(HttpMethod.POST, path, handler)
    fun put(path: String, handler: Handler<T>) = addRoute(HttpMethod.PUT, path, handler)
    fun update(path: String, handler: Handler<T>) = addRoute(HttpMethod.UPDATE, path, handler)
    fun delete(path: String, handler: Handler<T>) = addRoute(HttpMethod.DELETE, path, handler)

    fun path(path: String, body: ApiBuilder<T>.() -> Unit) {
        val child = ApiBuilder(componentsClass, prefix + path, auth)
        children.add(child)
        child.body()
    }

    fun auth(auth: Auth, body: ApiBuilder<T>.() -> Unit) {
        // not sure about this. the alternative is to allow nesting and the inner block applies.
        // this seems misleading to me. common sense says that nesting an endpoint inside multiple
        // auth blocks means it would be subject to multiple auth strategies. which isn't true
        // and wouldn't make sense.
        // if I did that then the auth fields could be non-nullable and default to None
        if (this.auth != null) throw IllegalStateException("auth blocks cannot be nested")
        val child = ApiBuilder(componentsClass, prefix, auth)
        children.add(child)
        child.body()
    }

    private fun addRoute(method: HttpMethod, path: String, handler: Handler<T>) =
        routes.add(Route(method, prefix + path, handler, auth))

    internal fun build(): Api<T> = Api(routes + children.flatMap { it.routes }, componentsClass)
}

/**
 * TODO docs - emphasise that code should get everything from the ApiComponents and not create components or capture them
 * explain that the API instance is created both during deployment and at runtime
 */
fun <T : ApiComponents> api(componentsType: KClass<T>, body: ApiBuilder<T>.() -> Unit): Api<T> {
    val builder = ApiBuilder(componentsType)
    builder.body()
    return builder.build()
}

/**
 * Provides all the components used by the implementation of the API.
 *
 * The code in the API runs with the components class as the implicit receiver
 * and can directly access its properties and methods.
 *
 * For example, if a data store is needed to handle a request, it would be provided by
 * the `ApiComponents` implementation:
 *
 *     class Components(val dataStore: DataStore) : ApiComponents
 *
 *     ...
 *
 *     get("/orders/{orderId}") { req ->
 *         val orderId = req.pathParams["orderId"]
 *         dataStore.loadOrderDetails(orderId)
 *     }
 */
@OsirisDsl
interface ApiComponents

// this can be replaced with a top-level val or function once they can be look up using reflection
interface ApiDefinition<in T : ApiComponents> {
    val api: Api<T>
}

/**
 * Creates the [ApiComponents] implementation used by the API code.
 *
 * There are two options when creating the API components:
 *   1) The `ApiComponents` implementation is created directly using a no-args constructor
 *   2) An `ApiComponentsFactory` is created using a no-args constructor and it creates the components
 *
 * Implementations of this interface must have a no-args constructor.
 *
 * TODO explain that the factory is created during deployment as well as at runtime so it shouldn't do any work
 * until createComponents is called
 */
interface ApiComponentsFactory<out T : ApiComponents> {
    val componentsClass: KClass<out T>
    fun createComponents(): T
}

/**
 * The authorisation mechanisms available in API Gateway.
 */
sealed class Auth(val name: String) {
    object None : Auth("NONE")
    object AwsIam : Auth("AWS_IAM")
    object CognitoUserPools : Auth("COGNITO_USER_POOLS")
    data class Custom(val authorizerId: String) : Auth("CUSTOM")
}

abstract class HttpException(val httpStatus: Int, message: String) : RuntimeException(message)

class DataNotFoundException(message: String) : HttpException(404, message) {
    constructor() : this("Not Found")
}

class ForbiddenException(message: String) : HttpException(403, message) {
    constructor() : this("Forbidden")
}
