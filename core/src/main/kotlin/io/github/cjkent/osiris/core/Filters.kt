package io.github.cjkent.osiris.core

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.regex.Pattern
import kotlin.reflect.KClass

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.core")

class Filter<T : ComponentsProvider> internal constructor(prefix: String, path: String, val handler: FilterHandler<T>) {

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
 * Creates a filter that is applied to all endpoints.
 *
 * If a filter only applies to a subset of endpoints it should be defined as part of the API.
 */
fun <T : ComponentsProvider> defineFilter(handler: FilterHandler<T>): Filter<T> = Filter("/*", handler)

/**
 * Filter that sets the default content type of the response.
 *
 * This is done by changing the `defaultResponseHeaders` of the request. This is propagated to
 * the response headers via [Request.responseBuilder] function.
 */
fun <T : ComponentsProvider> defaultContentTypeFilter(contentType: ContentType): Filter<T> {
    return defineFilter { req, handler ->
        val defaultHeaders = req.defaultResponseHeaders + (HttpHeaders.CONTENT_TYPE to contentType.header)
        val updatedReq = req.copy(defaultResponseHeaders = defaultHeaders)
        handler(this, updatedReq)
    }
}

/**
 * Filter that serialises the response body to JSON so it can be written to the response.
 *
 * This filter is only applied if the content type is `application/json`. For all other
 * content types the response is returned unchanged.
 *
 * Handling of body types:
 *   * null - no body
 *   * string - assumed to be JSON, used as-is
 *   * object - converted to a JSON string using Jackson
 *
 * @see defaultSerialisingFilter
 */
fun <T : ComponentsProvider> jsonSerialisingFilter(): Filter<T> {
    val gson = Gson()
    return defineFilter { req, handler ->
        val response = handler(this, req)
        val contentTypeHeader = response.headers[HttpHeaders.CONTENT_TYPE]
        val contentType = if (contentTypeHeader == null || contentTypeHeader == JSON_CONTENT_TYPE.header) {
            JSON_CONTENT_TYPE
        } else {
            ContentType.parse(contentTypeHeader)
        }
        if (contentType.mimeType == MimeTypes.APPLICATION_JSON) {
            response.copy(body = encodeBodyAsJson(response.body, gson))
        } else {
            response
        }
    }
}

/**
 * Filter that encodes the response received from the request handler code into a string that can be serialised into
 * the response body.
 *
 * This filter is only applied if the content type is *not* `application/json`. The response is returned unchanged
 * if the content type is JSON.
 *
 * Handling of body types:
 *   * null - no body
 *   * string - used as-is
 *   * ByteArray - base64 encoded
 *   * any other type throws an exception
 *
 * @see jsonSerialisingFilter
 */
fun <T : ComponentsProvider> defaultSerialisingFilter(): Filter<T> {
    return defineFilter { req, handler ->
        val response = handler(this, req)
        val contentTypeHeader = response.headers[HttpHeaders.CONTENT_TYPE]
        val contentType = if (contentTypeHeader == null || contentTypeHeader == JSON_CONTENT_TYPE.header) {
            JSON_CONTENT_TYPE
        } else {
            ContentType.parse(contentTypeHeader)
        }
        if (contentType.mimeType == MimeTypes.APPLICATION_JSON) {
            response
        } else {
            response.copy(body = encodeBody(response.body))
        }
    }
}

/**
 * The information describing an error.
 *
 * This is returned by a exception handler when an exception occurs and is used to build a response.
 */
data class ErrorInfo(val status: Int, val message: String?)

/**
 * Receives notification when an exception occurs and returns an object containing the HTTP status
 * and message used to build the response.
 */
class ExceptionHandler<T : Exception>(
    private val exceptionType: KClass<T>,
    private val handlerFn: (T) -> ErrorInfo
) {

    @Suppress("UNCHECKED_CAST")
    fun handle(exception: Exception): ErrorInfo? = when {
        exceptionType.java.isInstance(exception) -> handlerFn(exception as T)
        else -> null
    }
}

/**
 * Returns a filter that catches any exceptions thrown by the handler and builds a response containing the error
 * status and message.
 *
 * The exception is passed to each of the handlers in turn until one of them handles it. If none of them
 * handles it a 500 (server error) is returned with a generic message ("Server Error").
 */
fun <T : ComponentsProvider> exceptionMappingFilter(exceptionHandlers: List<ExceptionHandler<*>>): Filter<T> {
    // The info used when no handler is registered for the exception type
    return defineFilter { req, handler ->
        try {
            handler(this, req)
        } catch(e: Exception) {
            val info = exceptionHandlers.asSequence()
                .map { it.handle(e) }
                .filterNotNull()
                .firstOrNull()

            if (info != null) {
                Response.error(info.status, info.message)
            } else {
                log.error("Server Error", e)
                Response.error(500, "Server Error")
            }

        }
    }
}

/**
 * Creates an exception handler that returns an [ErrorInfo] for exceptions of a specific type.
 *
 * The info is used to build the response.
 *
 * The handler will handle any exceptions that are a subtype of the exception type.
 */
inline fun <reified T : Exception> exceptionHandler(noinline handlerFn: (T) -> ErrorInfo): ExceptionHandler<T> =
    ExceptionHandler(T::class, handlerFn)

/**
 * Returns a filter that catches any exceptions thrown by the handler and builds a response containing the error
 * status and message.
 *
 * The filter catches all subclasses of the listed exceptions. Any other exceptions will result in a status
 * of 500 (server error) with a generic error message.
 *
 * This catches
 *   * [HttpException], returns the status and message from the exception
 *   * [IllegalArgumentException], returns a status of 400 (bad request) and the exception message
 */
fun <T : ComponentsProvider> defaultExceptionMappingFilter(): Filter<T> {
    val handlers = listOf(
        exceptionHandler<HttpException> { ErrorInfo(it.httpStatus, it.message) },
        exceptionHandler<IllegalArgumentException> { ErrorInfo(400, it.message) })
    return exceptionMappingFilter(handlers)
}


/**
 * The standard set of filters applied to every endpoint in an API by default.
 *
 * If the filters need to be customised a list of filters should be provided to the [api] function.
 */
object StandardFilters {
    fun <T : ComponentsProvider> create(): List<Filter<T>> {
        return listOf(
            defaultExceptionMappingFilter(),
            defaultContentTypeFilter(JSON_CONTENT_TYPE),
            jsonSerialisingFilter(),
            defaultSerialisingFilter())
    }
}

//--------------------------------------------------------------------------------------------------

data class EncodedBody(val body: String?, val isBase64Encoded: Boolean)

/**
 * Encodes the response received from the request handler code into a string that can be serialised into
 * the response body.
 *
 * Handling of body types:
 *   * null - no body
 *   * string - used as-is
 *   * ByteArray - base64 encoded
 *   * any other type throws an exception
 */
private fun encodeBody(body: Any?): EncodedBody = when (body) {
    is EncodedBody -> body
    null, is String -> EncodedBody(body as String?, false)
    is ByteArray -> EncodedBody(String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8), true)
    else -> throw RuntimeException("Cannot convert value of type ${body.javaClass.name} to response body")
}

/**
 * Encodes the response received from the request handler code into a JSON string that can be serialised into
 * the response body.
 *
 * * null - no body
 * * string - assumed to be JSON, used as-is
 * * object - converted to a JSON string using Jackson
 */
private fun encodeBodyAsJson(body: Any?, gson: Gson): EncodedBody = when (body) {
    is EncodedBody -> body
    null, is String -> EncodedBody(body as String?, false)
    else -> EncodedBody(gson.toJson(body), false)
}

