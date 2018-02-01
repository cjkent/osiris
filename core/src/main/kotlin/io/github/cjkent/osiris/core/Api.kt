package io.github.cjkent.osiris.core

import java.util.regex.Pattern
import kotlin.reflect.KClass

/**
 * A model describing an API; it contains the routes to the API endpoints and the code executed
 * when the API receives requests.
 */
data class Api<T : ComponentsProvider>(
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
     * The code in the API definition runs with the components provider class as the implicit receiver
     * and can directly access its properties and methods.
     *
     * For example, if a data store is needed to handle a request, it would be provided by
     * the `ComponentsProvider` implementation:
     *
     *     class Components(val dataStore: DataStore) : ComponentsProvider
     *
     *     ...
     *
     *     get("/orders/{orderId}") { req ->
     *         val orderId = req.pathParams["orderId"]
     *         dataStore.loadOrderDetails(orderId)
     *     }
     */
    val componentsClass: KClass<in T>,
    /**
     * True if this API serves static files.
     */
    val staticFiles: Boolean
) {
    companion object {

        /**
         * Merges multiple APIs into a single API.
         *
         * The APIs must not defines any endpoints with the same path and method.
         *
         * This is intended to allow large APIs to be defined across multiple files:
         *
         *     val api1 = api<Foo> {
         *         get("/bar") {
         *            ...
         *         }
         *     }
         *
         *     val api2 = api<Foo> {
         *         get("/baz") {
         *            ...
         *         }
         *     }
         *
         *     // An API containing all the endpoints and filters from `api1` and `api2`
         *     val api = Api.merge(api1, api2)
         */
        inline fun <reified T : ComponentsProvider> merge(api1: Api<T>, api2: Api<T>, vararg rest: Api<T>): Api<T> {
            val apis = listOf(api1, api2) + rest
            val routes = apis.map { it.routes }.reduce { allRoutes, apiRoutes -> allRoutes + apiRoutes }
            val filters = apis.map { it.filters }.reduce { allFilters, apiFilters -> allFilters + apiFilters }
            val staticFiles = apis.map { it.staticFiles }.reduce { sf1, sf2 -> sf1 || sf2}
            return Api(routes, filters, T::class, staticFiles)
        }
    }
}

/**
 * This function is the entry point to the DSL used for defining an API.
 *
 * It is normally used to populate a top-level property named `api`. For example
 *
 *     val api = api(ExampleComponents::class) {
 *         get("/foo") { req ->
 *             ...
 *         }
 *     }
 *
 * The type parameter is the type of the implicit receiver of the handler code. This means the properties and
 * functions of that type are available to be used by the handler code. See [ComponentsProvider] for details.
 */
inline fun <reified T : ComponentsProvider> api(body: RootApiBuilder<T>.() -> Unit): Api<T> {
    val builder = RootApiBuilder(T::class)
    builder.body()
    return buildApi(builder)
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
 * Marks the DSL implicit receivers to avoid scoping problems.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
internal annotation class OsirisDsl

/**
 * This is an internal class that is part of the DSL implementation and should not be used by user code.
 */
@OsirisDsl
open class ApiBuilder<T : ComponentsProvider> internal constructor(
    internal val componentsClass: KClass<T>,
    private val prefix: String,
    private val auth: Auth?
) {

    internal var staticFilesBuilder: StaticFilesBuilder? = null
    internal val routes: MutableList<LambdaRoute<T>> = arrayListOf()
    internal val filters: MutableList<Filter<T>> = arrayListOf()
    private val children: MutableList<ApiBuilder<T>> = arrayListOf()

    /** Defines an endpoint that handles GET requests to the path. */
    fun get(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.GET, path, handler)

    /** Defines an endpoint that handles POST requests to the path. */
    fun post(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.POST, path, handler)

    /** Defines an endpoint that handles PUT requests to the path. */
    fun put(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.PUT, path, handler)

    /** Defines an endpoint that handles UPDATE requests to the path. */
    fun update(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.UPDATE, path, handler)

    /** Defines an endpoint that handles OPTIONS requests to the path. */
    fun options(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.OPTIONS, path, handler)

    /** Defines an endpoint that handles PATCH requests to the path. */
    fun patch(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.PATCH, path, handler)

    /** Defines an endpoint that handles DELETE requests to the path. */
    fun delete(path: String, handler: Handler<T>): Unit = addRoute(HttpMethod.DELETE, path, handler)

    fun filter(path: String, handler: FilterHandler<T>): Unit {
        filters.add(Filter(prefix, path, handler))
    }

    fun filter(handler: FilterHandler<T>): Unit = filter("/*", handler)

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

    fun staticFiles(body: StaticFilesBuilder.() -> Unit) {
        val staticFilesBuilder = StaticFilesBuilder(prefix, auth)
        staticFilesBuilder.body()
        this.staticFilesBuilder = staticFilesBuilder
    }

    //--------------------------------------------------------------------------------------------------

    private fun addRoute(method: HttpMethod, path: String, handler: Handler<T>) {
        routes.add(LambdaRoute(method, prefix + path, requestHandler(handler), auth ?: NoAuth))
    }

    internal fun descendants(): List<ApiBuilder<T>> = children + children.flatMap { it.descendants() }

    companion object {

        private fun <T : ComponentsProvider> requestHandler(handler: Handler<T>): RequestHandler<T> = { req ->
            val returnVal = handler(this, req)
            returnVal as? Response ?: req.responseBuilder().build(returnVal)
        }
    }
}

@OsirisDsl
class RootApiBuilder<T : ComponentsProvider> internal constructor(
    componentsClass: KClass<T>,
    prefix: String,
    auth: Auth?
) : ApiBuilder<T>(componentsClass, prefix, auth) {

    constructor(componentsType: KClass<T>) : this(componentsType, "", null)

    var globalFilters: List<Filter<T>> = StandardFilters.create()

    /**
     * Returns the static files configuration.
     *
     * This can be specified in any `ApiBuilder` in the API definition, but it must only be specified once.
     */
    internal fun effectiveStaticFiles(): StaticFiles? {
        val allStaticFiles = descendants().map { it.staticFilesBuilder } + staticFilesBuilder
        val nonNullStaticFiles = allStaticFiles.filter { it != null }
        if (nonNullStaticFiles.size > 1) {
            throw IllegalArgumentException("staticFiles must only be specified once")
        }
        return nonNullStaticFiles.firstOrNull()?.build()
    }
}

/**
 * Builds the API defined by the builder.
 *
 * This function is an implementation detail and not intended to be called by users.
 */
fun <T : ComponentsProvider> buildApi(builder: RootApiBuilder<T>): Api<T> {
    val allFilters = builder.globalFilters + builder.filters + builder.descendants().flatMap { it.filters }
    val allLambdaRoutes = builder.routes + builder.descendants().flatMap { it.routes }
    val wrappedRoutes = allLambdaRoutes.map { it.wrap(allFilters) }
    val effectiveStaticFiles = builder.effectiveStaticFiles()
    val allRoutes = when (effectiveStaticFiles) {
        null -> wrappedRoutes
        else -> wrappedRoutes + StaticRoute<T>(
            effectiveStaticFiles.path,
            effectiveStaticFiles.indexFile,
            effectiveStaticFiles.auth)
    }
    if (effectiveStaticFiles != null && !staticFilesPattern.matcher(effectiveStaticFiles.path).matches()) {
        throw IllegalArgumentException("Static files path is illegal: $effectiveStaticFiles")
    }
    val authTypes = allRoutes.map { it.auth }.filter { it != NoAuth }.toSet()
    if (authTypes.size > 1) throw IllegalArgumentException("Only one auth type is supported but found $authTypes")
    return Api(allRoutes, allFilters, builder.componentsClass, effectiveStaticFiles != null)
}

private val staticFilesPattern = Pattern.compile("/|(?:/[a-zA-Z0-9_\\-~.()]+)+")

class StaticFilesBuilder(
    private val prefix: String,
    private val auth: Auth?
) {
    var path: String? = null
    var indexFile: String? = null

    internal fun build(): StaticFiles {
        val localPath = path ?: throw IllegalArgumentException("Must specify the static files path")
        return StaticFiles(prefix + localPath, indexFile, auth ?: NoAuth)
    }
}

data class StaticFiles internal constructor(val path: String, val indexFile: String?, val auth: Auth)

/**
 * Provides all the components used by the implementation of the API.
 *
 * The code in the API runs with the components provider class as the implicit receiver
 * and can directly access its properties and methods.
 *
 * For example, if a data store is needed to handle a request, it would be provided by
 * the `ComponentsProvider` implementation:
 *
 *     class Components(val dataStore: DataStore) : ComponentsProvider
 *
 *     ...
 *
 *     get("/orders/{orderId}") { req ->
 *         val orderId = req.pathParams["orderId"]
 *         dataStore.loadOrderDetails(orderId)
 *     }
 */
@OsirisDsl
interface ComponentsProvider

/**
 * The authorisation strategy that should be applied to an endpoint in the API.
 */
interface Auth {
    /** The name of the authorisation strategy. */
    val name: String
}

/**
 * Authorisation strategy that allows anyone to call an endpoint in the API without authenticating.
 */
object NoAuth : Auth {
    override val name: String = "NONE"
}

/**
 * Base class for exceptions that should be automatically mapped to HTTP status codes.
 */
abstract class HttpException(val httpStatus: Int, message: String) : RuntimeException(message)

/**
 * Exception indicating that data could not be found at the specified location.
 *
 * This is thrown when a path doesn't match a resource and is translated to a
 * status of 404 (not found) by default.
 */
class DataNotFoundException(message: String = "Not Found") : HttpException(404, message)

/**
 * Exception indicating the caller is not authorised to access the resource.
 *
 * This is translated to a status of 403 (forbidden) by default.
 */
class ForbiddenException(message: String = "Forbidden") : HttpException(403, message)
