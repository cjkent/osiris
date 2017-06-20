package io.github.cjkent.osiris.api

import java.net.URLDecoder
import java.util.Locale
import java.util.regex.Pattern
import kotlin.reflect.KClass

const val API_COMPONENTS_CLASS = "API_COMPONENTS_CLASS"
const val API_DEFINITION_CLASS = "API_DEFINITION_CLASS"

/**
 * An API definition is defined by a user to create an API; it is the most important top-level type in Osiris.
 *
 * Implementations of `ApiDefinition` should use the [api] function to create the [Api].
 */
interface ApiDefinition<T : ApiComponents> {
    val api: Api<T>
}

/**
 * A model describing an API; it contains the routes to the API endpoints and the code executed
 * when the API receives requests.
 */
data class Api<T : ApiComponents>(
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
     * Filters applied to requests before they are passed to a handler.
     */
    val filters: List<Filter<T>>,
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
    val componentsClass: KClass<in T>
)

/**
 * This function is the entry point to the DSL used for defining an API.
 *
 * It is normally used to populate the field [ApiDefinition.api]. For example
 *
 *     class ExampleApiDefinition : ApiDefinition<ExampleComponents> {
 *
 *         override val api = api(ExampleComponents::class) {
 *             get("/foo") { req ->
 *                 ...
 *             }
 *         }
 *     }
 *
 * The type parameter is the type of the implicit receiver of the handler code. This means the properties and
 * functions of that type are available to be used by the handler code. See [ApiComponents] for details.
 */
fun <T : ApiComponents> api(
    componentsType: KClass<T>,
    filters: List<Filter<T>> = StandardFilters.create(),
    body: ApiBuilder<T>.() -> Unit
): Api<T> {

    val builder = ApiBuilder(filters, componentsType)
    builder.body()
    return builder.build()
}

/**
 * A set of HTTP parameters provided as part of request; can represent headers, path parameters or
 * query string parameters.
 *
 * This does not support repeated values in query strings as API Gateway doesn't support them.
 * For example, a query string of `foo=123&foo=456` will only contain one value for `foo`. It is
 * undefined which one.
 */
class Params(params: Map<String, String>?) {

    constructor() : this(mapOf())

    val params: Map<String, String> = params ?: mapOf()

    /** Returns the named parameter. */
    operator fun get(name: String): String? = params[name]

    /** Returns the named parameter or throws `IllegalArgumentException` if there is no parameter with the name. */
    fun required(name: String): String = get(name) ?: throw IllegalArgumentException("No value named '$name'")

    companion object {

        /** Creates a set of parameters by parsing an HTTP query string. */
        fun fromQueryString(queryString: String?): Params = when {
            queryString == null || queryString.trim().isEmpty() -> Params(mapOf())
            else -> Params(URLDecoder.decode(queryString, "UTF-8").split("&").map { splitVar(it) }.toMap())
        }

        private fun splitVar(nameAndValue: String): Pair<String, String> {
            val index = nameAndValue.indexOf('=')
            return when (index) {
                -1 -> Pair(nameAndValue, "")
                else -> Pair(nameAndValue.substring(0, index), nameAndValue.substring(index + 1, nameAndValue.length))
            }
        }
    }
}

// TODO add field bodyObj: Any? to allow filters to pre-process the body?
// e.g. could look at the content type of the request body and parse the body into an object. the handler would handle
// the object and the parsing could be isolated to filters, one for each content type. new content types could be
// supported by adding a new filter
/**
 * Contains the details of an HTTP request received by the API.
 */
data class Request(
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
    val body: String? = null,
    val bodyIsBase64Encoded: Boolean = false,
    val defaultResponseHeaders: Map<String, String> = mapOf()
) {

    internal val requestPath: RequestPath = RequestPath(path)

    /** Returns the body or throws `IllegalArgumentException` if it is null. */
    fun requireBody(): String = body ?: throw IllegalArgumentException("Request body is required")

    /**
     * Returns a builder for building a response.
     *
     * This is used to customise the headers or the status of the response.
     */
    fun responseBuilder(): ResponseBuilder =
        ResponseBuilder(defaultResponseHeaders.toMutableMap())
}

/**
 * Standard HTTP header names.
 */
object HttpHeaders {
    const val CONTENT_TYPE = "Content-Type"
}

// TODO include encoding? need to confirm what API gateway uses. presumably UTF-8
/**
 * Standard content types.
 */
object ContentTypes {
    const val APPLICATION_JSON = "application/json"
    const val APPLICATION_XML = "application/xml"
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
    fun build(body: Any? = null): Response = Response(httpStatus, Headers(headers), body)
}

/**
 * The details of the HTTP response returned from the code handling a request.
 *
 * It is only necessary to return a `Response` when the headers or status need to be customised.
 * In many cases it is sufficient to return a value that is serialised into the response body
 * and has a status of 200 (OK).
 *
 * Responses should be created using the builder returned by [Request.responseBuilder]. The builder
 * will be initialised with the default response headers so the user only needs to specify the
 * headers whose values they wish to change.
 */
data class Response internal constructor(val status: Int, val headers: Headers, val body: Any?) {
    companion object {
        internal fun error(status: Int, message: String): Response {
            val headers = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.TEXT_PLAIN)
            return Response(status, Headers(headers), message)
        }
    }
}

/** A map of HTTP headers that looks up values in a case-insensitive fashion (in accordance with the HTTP spec). */
class Headers(val headerMap: Map<String, String> = mapOf()) {

    private val lookupMap = headerMap.mapKeys { (key, _) -> key.toLowerCase(Locale.ENGLISH) }

    operator fun get(key: String): String? = lookupMap[key.toLowerCase(Locale.ENGLISH)]
}

enum class HttpMethod {
    GET,
    POST,
    PUT,
    UPDATE,
    DELETE
}

/**
 * The type of the lambdas in the DSL containing the code that runs when a request is received.
 */
typealias Handler<T> = T.(Request) -> Any

// This causes the compiler to crash
//typealias FilterHandler<T> = T.(Request, Handler<T>) -> Any
// This is equivalent to the line above but doesn't make the compiler crash
typealias FilterHandler<T> = T.(Request, T.(Request) -> Response) -> Any

/**
 * The type of the handler passed to a [Filter].
 *
 * Handlers and filters can return objects of any type (see [Handler]). If the returned value is
 * not a [Response] the library wraps it in a `Response` before returning it to the caller. This
 * ensures that the objects returned to a `Filter` implementation is guaranteed to be a `Response`.
 */
typealias RequestHandler<T> = T.(Request) -> Response

/**
 * Pattern matching resource paths; matches regular segments like `/foo` and variable segments like `/{foo}` and
 * any combination of the two.
 */
internal val pathPattern = Pattern.compile("/|(?:(?:/[a-zA-Z0-9_\\-~.()]+)|(?:/\\{[a-zA-Z0-9_\\-~.()]+}))+")

/**
 * A route describes one endpoint in a REST API.
 *
 * A route contains
 *
 *   * The HTTP method it accepts, for example GET or POST
 *   * The path to the endpoint, for example `/foo/bar`
 *   * The code that is run when the endpoint receives a request (the "handler")
 *   * The authorisation needed to invoke the endpoint
 */
data class Route<T : ApiComponents>(
    val method: HttpMethod,
    val path: String,
    val handler: RequestHandler<T>,
    val auth: Auth? = null
) {

    init {
        validatePath(path)
    }

    internal fun wrap(filters: List<Filter<T>>): Route<T> {
        val chain = filters.reversed().fold(handler, { requestHandler, filter -> wrapFilter(requestHandler, filter) })
        return copy(handler = chain)
    }

    private fun wrapFilter(handler: RequestHandler<T>, filter: Filter<T>): RequestHandler<T> {
        return { req ->
            val returnVal = when {
                filter.matches(req) -> filter.handler(this, req, handler)
                else -> handler(this, req)
            }
            returnVal as? Response ?: req.responseBuilder().build(returnVal)
        }
    }

    companion object {

        // TODO read the RFC in case there are any I've missed
        internal fun validatePath(path: String) {
            if (!pathPattern.matcher(path).matches()) throw IllegalArgumentException("Illegal path " + path)
        }
    }
}

class Filter<T : ApiComponents> internal constructor(prefix: String, path: String, val handler: FilterHandler<T>) {

    internal constructor(path: String, handler: FilterHandler<T>) : this("", path, handler)

    private val segments: List<String> = (prefix + path).split('/').map { it.trim() }.filter { !it.isEmpty() }

    init {
        if (!prefix.isEmpty() && !pathPattern.matcher(prefix).matches()) {
            throw IllegalArgumentException("Filter prefix format is illegal: $prefix")
        }
        if (!filterPattern.matcher(path).matches()) {
            throw IllegalArgumentException("Filter path is illegal: $path")
        }
    }

    internal fun matches(request: Request): Boolean = matches(request.requestPath.segments)

    // this is separate from the function above for easier testing
    internal fun matches(requestSegments: List<String>): Boolean {
        tailrec fun matches(idx: Int): Boolean {
            if (idx == segments.size) return false
            val filterSegment = segments[idx]
            // If the filter paths ends /* then it matches everything
            if (idx == segments.size - 1 && filterSegment.isWildcard) return true
            if (idx == requestSegments.size) return false
            val requestSegment = requestSegments[idx]
            if (filterSegment != requestSegment && !filterSegment.isWildcard) return false
            if (idx == segments.size - 1 && idx == requestSegments.size - 1) return true
            return matches(idx + 1)
        }
        return matches(0)
    }

    private val String.isWildcard: Boolean get() = this == "*" || (this.startsWith('{') && this.endsWith('}'))

    companion object {
        /** Pattern matching the path passed in when creating a filter; allows wildcards but no part variables. */
        internal val filterPattern = Pattern.compile("(?:(?:/[a-zA-Z0-9_\\-~.()]+)|(?:/\\*))+")
    }
}

/**
 * Marks the DSL implicit receivers to avoid scoping problems.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
internal annotation class OsirisDsl

// TODO move to Model.kt?
/**
 * This is an internal class that is part of the DSL implementation and should not be used by user code.
 */
@OsirisDsl
class ApiBuilder<T : ApiComponents> private constructor(
    filters: List<Filter<T>>,
    val componentsClass: KClass<T>,
    val prefix: String,
    val auth: Auth?
) {

    constructor(filters: List<Filter<T>>, componentsType: KClass<T>) : this(filters, componentsType, "", null)

    private val routes: MutableList<Route<T>> = arrayListOf()
    private val filters: MutableList<Filter<T>> = arrayListOf(*filters.toTypedArray())
    private val children: MutableList<ApiBuilder<T>> = arrayListOf()

    // TODO document all of these with an example.
    fun get(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.GET, path, handler)

    fun post(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.POST, path, handler)
    fun put(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.PUT, path, handler)
    fun update(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.UPDATE, path, handler)
    fun delete(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.DELETE, path, handler)

    fun filter(path: String, handler: FilterHandler<T>): Unit {
        filters.add(Filter(prefix, path, handler))
    }

    fun filter(handler: FilterHandler<T>): Unit = filter("/*", handler)

    fun path(path: String, body: ApiBuilder<T>.() -> Unit) {
        val child = ApiBuilder(listOf(), componentsClass, prefix + path, auth)
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
        val child = ApiBuilder(listOf(), componentsClass, prefix, auth)
        children.add(child)
        child.body()
    }

    private fun addRoute(method: HttpMethod, path: String, handler: Handler<T>) {
        routes.add(Route(method, prefix + path, requestHandler(handler), auth))
    }

    internal fun build(): Api<T> {
        val allFilters = filters + children.flatMap { it.filters }
        val allRoutes = routes + children.flatMap { it.routes }
        val wrappedRoutes = allRoutes.map { it.wrap(allFilters) }
        return Api(wrappedRoutes, allFilters, componentsClass)
    }

    companion object {
        private fun <T : ApiComponents> requestHandler(handler: Handler<T>): RequestHandler<T> = { req ->
            val returnVal = handler(this, req)
            returnVal as? Response ?: req.responseBuilder().build(returnVal)
        }

    }
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

open class Foo
class Bar : Foo()
class Container<in T : Foo>(val fn: T.() -> Any) {
    fun fn(foo: T): Any = foo.fn()
}

fun main(args: Array<String>) {
    val container = Container<Foo> { "whatever" }
    val bar = Bar()
    container.fn(bar)
}
