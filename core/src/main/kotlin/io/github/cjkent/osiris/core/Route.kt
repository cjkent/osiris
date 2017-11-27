package io.github.cjkent.osiris.core

/**
 * A route describes one endpoint in a REST API.
 *
 * A route contains
 *
 *   * The HTTP method it accepts, for example GET or POST
 *   * The path to the endpoint, for example `/foo/bar`
 *   * The authorisation needed to invoke the endpoint
 */
sealed class Route<T : ComponentsProvider> {

    abstract val path: String
    abstract val auth: Auth

    companion object {

        // TODO read the RFC in case there are any I've missed
        internal fun validatePath(path: String) {
            if (!pathPattern.matcher(path).matches()) throw IllegalArgumentException("Illegal path " + path)
        }
    }
}

/**
 * Describes an endpoint in a REST API whose requests are handled by a lambda.
 *
 * It contains
 *
 *   * The HTTP method it accepts, for example GET or POST
 *   * The path to the endpoint, for example `/foo/bar`
 *   * The code that is run when the endpoint receives a request (the "handler")
 *   * The authorisation needed to invoke the endpoint
 */
data class LambdaRoute<T : ComponentsProvider>(
    val method: HttpMethod,
    override val path: String,
    val handler: RequestHandler<T>,
    override val auth: Auth = NoAuth
): Route<T>() {

    init {
        validatePath(path)
    }

    internal fun wrap(filters: List<Filter<T>>): LambdaRoute<T> {
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
}

/**
 * Describes an endpoint in a REST API that serves static files.
 *
 * It contains
 *
 *   * The HTTP method it accepts, must be GET, HEAD or OPTIONS
 *   * The path to the endpoint, for example `/foo/bar`
 *   * The authorisation needed to invoke the endpoint
 */
data class StaticRoute<T : ComponentsProvider>(
    override val path: String,
    val indexFile: String?,
    override val auth: Auth = NoAuth
) : Route<T>() {

    init {
        validatePath(path)
        // TODO validate the method and index file
    }
}
