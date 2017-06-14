package io.github.cjkent.osiris.api

import java.util.regex.Pattern

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

internal class SubRoute<T : ApiComponents> private constructor(val route: Route<T>, val segments: List<Segment>) {

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
        else -> SubRoute(route, segments.slice(1..segments.size - 1))
    }
}

sealed class RouteNode<T : ApiComponents>(
    val name: String,
    val handlers: Map<HttpMethod, Pair<RequestHandler<T>, Auth?>>,
    val fixedChildren: Map<String, FixedRouteNode<T>>,
    val variableChild: VariableRouteNode<T>?
) {

    companion object {

        fun <T : ApiComponents> create(api: Api<T>): RouteNode<T> =
            node(FixedSegment(""), api.routes.map { SubRoute(it) }, api.filters)

        // For testing
        internal fun <T : ApiComponents> create(vararg routes: Route<T>): RouteNode<T> =
            node(FixedSegment(""), routes.map { SubRoute(it) }, listOf())

        private fun <T : ApiComponents> node(
            segment: Segment,
            routes: List<SubRoute<T>>,
            filters: List<Filter<T>>
        ): RouteNode<T> {

            // empty routes matches this node. there can be 1 per HTTP method
            val (emptyRoutes, nonEmptyRoutes) = routes.partition { it.isEmpty() }

            val handlersByMethod = emptyRoutes
                .groupBy { it.route.method }
                .mapValues { (_, routes) -> createHandler(routes) }

            // non-empty routes form the child nodes
            val (fixedRoutes, variableRoutes) = nonEmptyRoutes.partition { it.head() is FixedSegment }

            // group fixed routes by the first segment - there is one node per unique segment name
            val fixedRoutesBySegment = fixedRoutes.groupBy { it.head() as FixedSegment }
            val fixedChildren = fixedRoutesBySegment
                .mapValues { (_, routes) -> routes.map { it.tail() } }
                .mapValues { (segment, tailRoutes) -> node(segment, tailRoutes, filters) as FixedRouteNode<T> }
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
                segment -> node(segment, variableRoutes.map { it.tail() }, filters) as VariableRouteNode<T>
            }
            return when (segment) {
                is FixedSegment -> FixedRouteNode(segment.pathPart, handlersByMethod, fixedChildren, variableChild)
                is VariableSegment -> VariableRouteNode(segment.variableName, handlersByMethod, fixedChildren, variableChild)
            }
        }

        private fun <T : ApiComponents> createHandler(routes: List<SubRoute<T>>): Pair<RequestHandler<T>, Auth?> =
            if (routes.size == 1) {
                val route = routes[0].route
                Pair(route.handler, route.auth)
            } else {
                val routeStrs = routes.map { "${it.route.method.name} ${it.route.path}" }.toSet()
                throw IllegalArgumentException("Multiple routes with the same HTTP method $routeStrs")
            }
    }
}

class FixedRouteNode<T : ApiComponents>(
    name: String,
    handlers: Map<HttpMethod, Pair<RequestHandler<T>, Auth?>>,
    fixedChildren: Map<String, FixedRouteNode<T>>,
    variableChild: VariableRouteNode<T>?
) : RouteNode<T>(name, handlers, fixedChildren, variableChild)

class VariableRouteNode<T : ApiComponents>(
    name: String,
    handlers: Map<HttpMethod, Pair<RequestHandler<T>, Auth?>>,
    fixedChildren: Map<String, FixedRouteNode<T>>,
    variableChild: VariableRouteNode<T>?
) : RouteNode<T>(name, handlers, fixedChildren, variableChild)

fun RouteNode<*>.prettyPrint(): String {
    fun RouteNode<*>.prettyPrint(builder: StringBuilder, indent: String) {
        builder.append(indent).append("/")
        val pathPart = when (this) {
            is FixedRouteNode<*> -> name
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
        isEmpty() -> throw IllegalStateException("Cannot take the head of an empty RequestSubPath")
        else -> segments[0]
    }

    fun tail(): RequestPath = when {
        isEmpty() -> throw IllegalStateException("Cannot take the tail of an empty RequestSubPath")
        else -> RequestPath(path, segments.slice(1..segments.size - 1))
    }

    companion object {
        fun split(path: String): List<String> = path.split("/").map { it.trim() }.filter { !it.isEmpty() }
    }
}

data class RouteMatch<in T : ApiComponents>(val handler: RequestHandler<T>, val vars: Map<String, String>)

fun <T : ApiComponents> RouteNode<T>.match(method: HttpMethod, path: String): RouteMatch<T>? {

    fun <T : ApiComponents> RouteNode<T>.match(
        method: HttpMethod,
        path: RequestPath,
        vars: Map<String, String>
    ): RouteMatch<T>? {

        if (path.isEmpty()) return handlers[method]?.let { RouteMatch(it.first, vars) }
        val head = path.head()
        val tail = path.tail()
        val fixedMatch = fixedChildren[head]?.match(method, tail, vars)
        return fixedMatch ?: variableChild?.match(method, tail, vars + (variableChild.name to head))
    }
    return match(method, RequestPath(path), mapOf())
}

