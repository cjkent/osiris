package io.github.cjkent.osiris.core

import java.util.regex.Pattern

/**
 * A segment in an HTTP route; a segment represents the part of a route between two slashes, for example the route
 * `/foo/bar` contains two segments, one for `foo` and one for `bar`.
 */
sealed class Segment {

    companion object {

        private val variableSegmentPattern = Pattern.compile("\\{\\w+}")

        fun create(pathPart: String): Segment =
            if (variableSegmentPattern.matcher(pathPart).matches()) {
                VariableSegment(pathPart.substring(1..pathPart.length - 2))
            } else {
                FixedSegment(pathPart)
            }
    }
}

data class FixedSegment(val pathPart: String) : Segment()

data class VariableSegment(val variableName: String) : Segment()

internal class SubRoute<T : ComponentsProvider> private constructor(val route: Route<T>, val segments: List<Segment>) {

    constructor(route: Route<T>) : this(route, segments(route.path))

    companion object {
        private fun segments(path: String): List<Segment> =
            path.split('/').map { it.trim() }.filter { !it.isEmpty() }.map { Segment.create(it) }
    }

    fun isEmpty(): Boolean = segments.isEmpty()

    fun head(): Segment = when {
        isEmpty() -> throw IllegalStateException("Cannot take the head of an empty sub-route")
        else -> segments[0]
    }

    fun tail(): SubRoute<T> = when {
        isEmpty() -> throw IllegalStateException("Cannot take the tail of an empty sub-route")
        else -> SubRoute(route, segments.slice(1 until segments.size))
    }
}

/**
 * A node in the tree of routes that make up an API.
 *
 * The routes in an API can be represented as a tree. The route at the base of the API `/` is represented by
 * the `RouteNode` at the root of the tree. Each node in the tree corresponds to one section of a route.
 *
 * For example, consider an API with the following routes:
 * ```
 * /foo
 * /foo/bar
 * /foo/baz
 * /qux
 * ```
 * It is represented by the following tree:
 * ```
 * /
 *  |- foo
 *  |   |- bar
 *  |   `- baz
 *  `- qux
 * ```
 */
sealed class RouteNode<T : ComponentsProvider>(

    /** The name of the node; For a fixed node this is the path part, for a variable node it's the variable name. */
    val name: String,

    /** Handlers (and their associated [Auth]) keyed by the HTTP method they handle */
    val handlers: Map<HttpMethod, Pair<RequestHandler<T>, Auth?>>,

    /** The fixed node children of this node, keyed by their path part. */
    val fixedChildren: Map<String, RouteNode<T>>,

    /** The variable node that is a child of this node; has a variable path part, e.g. `{foo}`. */
    val variableChild: VariableRouteNode<T>?
) {

    companion object {

        /**
         * Creates a tree of `RouteNode` instances representing the routes in the API.
         *
         * @return the root node
         */
        fun <T : ComponentsProvider> create(api: Api<T>): RouteNode<T> =
            node(FixedSegment(""), api.routes.map { SubRoute(it) }, api.filters)

        /**
         * Creates a tree of `RouteNode` instances representing the routes.
         *
         * @return the root node
         */
        internal fun <T : ComponentsProvider> create(
            routes: List<Route<T>>,
            filters: List<Filter<T>> = listOf()
        ): RouteNode<T> = node(FixedSegment(""), routes.map { SubRoute(it) }, filters)

        /**
         * Creates a tree of `RouteNode` instances representing the routes.
         *
         * For testing.
         *
         * @return the root node
         */
        internal fun <T : ComponentsProvider> create(vararg routes: Route<T>): RouteNode<T> =
            node(FixedSegment(""), routes.map { SubRoute(it) }, listOf())

        /**
         * TODO can this be split into 3 functions including fixedNode and variableNode?
         */
        private fun <T : ComponentsProvider> node(
            segment: Segment,
            routes: List<SubRoute<T>>,
            filters: List<Filter<T>>
        ): RouteNode<T> {

            // empty routes matches this node. there can be 1 per HTTP method
            val (emptyRoutes, nonEmptyRoutes) = routes.partition { it.isEmpty() }
            val isStaticEndpoint = emptyRoutes.any { it.route is StaticRoute<*> }
            val handlersByMethod = if (isStaticEndpoint) {
                mapOf()
            } else {
                emptyRoutes
                    .groupBy { ((it.route) as LambdaRoute<T>).method }
                    .mapValues { (_, routes) -> createHandler(routes) }
            }
            // non-empty routes form the child nodes
            val (fixedRoutes, variableRoutes) = nonEmptyRoutes.partition { it.head() is FixedSegment }

            // group fixed routes by the first segment - there is one node per unique segment name
            val fixedRoutesBySegment = fixedRoutes.groupBy { it.head() as FixedSegment }
            val fixedChildren = fixedRoutesBySegment
                .mapValues { (_, routes) -> routes.map { it.tail() } }
                .mapValues { (segment, tailRoutes) -> node(segment, tailRoutes, filters) }
                .mapKeys { (segment, _) -> segment.pathPart }

            // The variable segment of the variable routes, e.g. bar in /foo/{bar}/baz
            val variableSegments = variableRoutes.map { it.head() }.toSet()
            // API gateway only allows a single variable name for a variable segment
            // so there can't be two routes passing through the same variable node using a different variable name
            // /foo/{bar} and /foo/{bar}/baz is OK
            // /foo/{bar} and /foo/{qux}/baz is not as /foo/{bar} and /foo/{qux} are the same route but with
            // a different variable name in the same location
            if (variableSegments.size > 1) {
                throw IllegalArgumentException("Routes found with clashing variable names: $variableRoutes")
            }
            val variableSegment = variableSegments.firstOrNull()
            val variableChild = variableSegment?.let {
                node(it, variableRoutes.map { it.tail() }, filters) as VariableRouteNode<T>
            }
            return if (isStaticEndpoint) {
                if (variableSegment != null) {
                    // TODO a static endpoint shouldn't have any variables anywhere but that's harder to enforce
                    throw IllegalArgumentException("A static endpoint must not have any variable children")
                }
                if (emptyRoutes.size > 1) {
                    throw IllegalArgumentException("A static endpoint must be the only method for its path")
                }
                if (segment !is FixedSegment) {
                    throw IllegalArgumentException("A static endpoint must not end with a variable path part")
                }
                val staticRoute = emptyRoutes[0].route as StaticRoute<*>
                StaticRouteNode(segment.pathPart, fixedChildren, staticRoute.auth, staticRoute.indexFile)
            } else {
                when (segment) {
                    is FixedSegment -> FixedRouteNode(
                        segment.pathPart,
                        handlersByMethod,
                        fixedChildren,
                        variableChild
                    )
                    is VariableSegment -> VariableRouteNode(
                        segment.variableName,
                        handlersByMethod,
                        fixedChildren,
                        variableChild
                    )
                }
            }
        }

        // These SubRoutes are guaranteed to contain LambdaRoutes but the type system doesn't know
        private fun <T : ComponentsProvider> createHandler(routes: List<SubRoute<T>>): Pair<RequestHandler<T>, Auth> =
            if (routes.size == 1) {
                val route = routes[0].route as LambdaRoute<T>
                Pair(route.handler, route.auth)
            } else {
                val routeStrs = routes.map { "${(it.route as LambdaRoute<T>).method.name} ${it.route.path}" }.toSet()
                throw IllegalArgumentException("Multiple routes with the same HTTP method $routeStrs")
            }
    }
}

/**
 * Node representing an endpoint ending with a fixed segment, e.g. `/foo/bar`.
 */
class FixedRouteNode<T : ComponentsProvider>(
    name: String,
    handlers: Map<HttpMethod, Pair<RequestHandler<T>, Auth>>,
    fixedChildren: Map<String, RouteNode<T>>,
    variableChild: VariableRouteNode<T>?
) : RouteNode<T>(name, handlers, fixedChildren, variableChild)

/**
 * Node representing an endpoint ending with a variable segment, e.g. `/foo/{bar}`.
 */
class VariableRouteNode<T : ComponentsProvider>(
    name: String,
    handlers: Map<HttpMethod, Pair<RequestHandler<T>, Auth>>,
    fixedChildren: Map<String, RouteNode<T>>,
    variableChild: VariableRouteNode<T>?
) : RouteNode<T>(name, handlers, fixedChildren, variableChild)

/**
 * Node representing an endpoint serving static files from S3.
 */
class StaticRouteNode<T : ComponentsProvider>(
    name: String,
    fixedChildren: Map<String, RouteNode<T>>,
    val auth: Auth,
    val indexFile: String?
) : RouteNode<T>(name, mapOf(), fixedChildren, null)

/**
 * Returns a pretty-printed string showing the node and its children in a tree structure.
 */
fun RouteNode<*>.prettyPrint(): String {
    fun RouteNode<*>.prettyPrint(builder: StringBuilder, indent: String) {
        builder.append(indent).append("/")
        val pathPart = when (this) {
            is FixedRouteNode<*> -> name
            is StaticRouteNode<*> -> name
            is VariableRouteNode<*> -> "{$name}"
        }
        builder.append(pathPart)
        builder.append(" ")
        if (!handlers.isEmpty()) builder.append(handlers.keys.map { it.name })
        for (fixedChild in fixedChildren.values) {
            builder.append("\n")
            fixedChild.prettyPrint(builder, "  " + indent)
        }
        variableChild?.apply {
            builder.append("\n")
            prettyPrint(builder, "  " + indent)
        }
    }

    val stringBuilder = StringBuilder()
    prettyPrint(stringBuilder, "")
    return stringBuilder.toString()
}

internal class RequestPath private constructor(val path: String, val segments: List<String>) {

    constructor(path: String) : this(path, split(path))

    fun isEmpty(): Boolean = segments.isEmpty()

    fun head(): String = when {
        isEmpty() -> throw IllegalStateException("Cannot take the head of an empty RequestPath")
        else -> segments[0]
    }

    fun tail(): RequestPath = when {
        isEmpty() -> throw IllegalStateException("Cannot take the tail of an empty RequestPath")
        else -> RequestPath(path, segments.slice(1 until segments.size))
    }

    companion object {
        fun split(path: String): List<String> = path.split("/").map { it.trim() }.filter { !it.isEmpty() }
    }
}

data class RouteMatch<in T : ComponentsProvider>(val handler: RequestHandler<T>, val vars: Map<String, String>)

fun <T : ComponentsProvider> RouteNode<T>.match(method: HttpMethod, path: String): RouteMatch<T>? {

    fun <T : ComponentsProvider> RouteNode<T>.match(reqPath: RequestPath, vars: Map<String, String>): RouteMatch<T>? {
        if (reqPath.isEmpty()) return handlers[method]?.let { RouteMatch(it.first, vars) }
        val head = reqPath.head()
        val tail = reqPath.tail()
        val fixedMatch = fixedChildren[head]?.match(tail, vars)
        return fixedMatch ?: variableChild?.match(tail, vars + (variableChild.name to head))
    }
    return match(RequestPath(path), mapOf())
}

