package io.github.cjkent.osiris.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64
import kotlin.reflect.KClass

/**
 * Creates a filter that is applied to all endpoints.
 *
 * If a filter only applies to a subset of endpoints it should be defined as part of the API.
 */
fun <T : ApiComponents> defineFilter(handler: FilterHandler<T>): Filter<T> = Filter("/*", handler)

/**
 * Filter that sets the default content type of the response.
 *
 * This is done by changing the `defaultResponseHeaders` of the request. This is propagated to
 * the response headers via [Request.responseBuilder] function.
 */
fun <T : ApiComponents> defaultContentTypeFilter(contentType: String): Filter<T> {
    return defineFilter { req, handler ->
        val defaultHeaders = req.defaultResponseHeaders + (HttpHeaders.CONTENT_TYPE to contentType)
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
fun <T : ApiComponents> jsonSerialisingFilter(): Filter<T> {
    val objectMapper = jacksonObjectMapper()
    return defineFilter { req, handler ->
        val response = handler(this, req)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE] ?: ContentTypes.APPLICATION_JSON
        when (contentType) {
            ContentTypes.APPLICATION_JSON -> response.copy(body = encodeBodyAsJson(response.body, objectMapper))
            else -> response
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
fun <T : ApiComponents> defaultSerialisingFilter(): Filter<T> {
    return defineFilter { req, handler ->
        val response = handler(this, req)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE] ?: ContentTypes.APPLICATION_JSON
        when (contentType) {
            ContentTypes.APPLICATION_JSON -> response
            else -> response.copy(body = encodeBody(response.body))
        }
    }
}

/**
 * The information describing an error.
 *
 * This is returned by a exception handler when an exception occurs and is used to build a response.
 */
data class ErrorInfo(val status: Int, val message: String)

/**
 * Receives notification when an exception occurs and returns an object containing the HTTP status
 * and message used to build the response.
 */
typealias ExceptionHandler = (Exception) -> ErrorInfo

fun <T : ApiComponents> exceptionMappingFilter(handlers: Map<KClass<out Exception>, ExceptionHandler>): Filter<T> {
    // The handler used when no handler is registered for the exception type
    val defaultHandler: ExceptionHandler = { ErrorInfo(500, "Server Error") }
    return defineFilter { req, handler ->
        try {
            handler(this, req)
        } catch(e: Exception) {
            val exHandler = handlers.entries.find { (exType, _) -> exType.java.isInstance(e) }?.value ?: defaultHandler
            val errorInfo = exHandler(e)
            Response.error(errorInfo.status, errorInfo.message)
        }
    }
}

fun <T : ApiComponents> defaultExceptionMappingFilter(): Filter<T> {
    val handlers = mapOf<KClass<out Exception>, ExceptionHandler>(
        HttpException::class to { ErrorInfo(it.) }
    )
    return exceptionMappingFilter(handlers)
}


/**
 * The standard set of filters applied to every endpoint in an API by default.
 *
 * If the filters need to be customised a list of filters should be provided to the [api] function.
 */
object StandardFilters {
    fun <T : ApiComponents> create(): List<Filter<T>> {
        return listOf(
//            defaultExceptionMappingFilter(),
            // TODO This is actually redundant, JSON is hard-coded as the default content type if there isn't one specified
            defaultContentTypeFilter(ContentTypes.APPLICATION_JSON),
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
private fun encodeBodyAsJson(body: Any?, objectMapper: ObjectMapper): EncodedBody = when (body) {
    null, is String -> EncodedBody(body as String?, false)
    else -> EncodedBody(objectMapper.writeValueAsString(body), false)
}

