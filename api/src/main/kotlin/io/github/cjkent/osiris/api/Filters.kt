package io.github.cjkent.osiris.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

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
 * Filter that serialises objects so they can be written to the response.
 *
 * Handling of body types by content type:
 *   * content type = JSON
 *     * null - no body
 *     * string - assumed to be JSON, used as-is, no base64 encoding
 *     * ByteArray - base64 encoded
 *     * object - converted to a JSON string using Jackson
 *   * content type != JSON
 *     * null - no body
 *     * string - used as-is, no base64 encoding
 *     * ByteArray - base64 encoded
 *     * any other type throws an exception
 */
fun <T : ApiComponents> serialisingFilter(): Filter<T> {
    val objectMapper = jacksonObjectMapper()
    return defineFilter { req, handler ->
        val response = handler(this, req)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE] ?: ContentTypes.APPLICATION_JSON
        response.copy(body = encodeBody(response.body, objectMapper, contentType))
    }
}

// TODO exception mapping filter

/**
 * The standard set of filters applied to every endpoint in an API by default.
 *
 * If the filters need to be customised a list of filters should be provided to the [api] function.
 */
object StandardFilters {
    fun <T : ApiComponents> create(): List<Filter<T>> {
        return listOf(
            // TODO This is actually redundant, JSON is hard-coded as the default content type if there isn't one specified
            defaultContentTypeFilter(ContentTypes.APPLICATION_JSON),
            serialisingFilter())
    }
}

//--------------------------------------------------------------------------------------------------

data class EncodedBody(val body: String?, val isBase64Encoded: Boolean)

/**
 * Encodes the response received from the request handler code into a string that can be serialised into
 * the response body.
 *
 * Handling of body types by content type:
 *   * content type = JSON
 *     * null - no body
 *     * string - assumed to be JSON, used as-is, no base64
 *     * ByteArray - base64 encoded
 *     * object - converted to a JSON string using Jackson
 *   * content type != JSON
 *     * null - no body
 *     * string - used as-is, no base64 - Jackson should handle escaping when AWS does the conversion
 *     * ByteArray - base64 encoded
 *     * any other type throws an exception
 */
internal fun encodeBody(body: Any?, objectMapper: ObjectMapper, contentType: String): EncodedBody {
    return if (contentType == ContentTypes.APPLICATION_JSON) {
        when (body) {
            null, is String -> EncodedBody(body as String?, false)
            is ByteArray -> EncodedBody(String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8), true)
            else -> EncodedBody(objectMapper.writeValueAsString(body), false)
        }
    } else {
        when (body) {
            null, is String -> EncodedBody(body as String?, false)
            is ByteArray -> EncodedBody(String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8), true)
            else -> throw RuntimeException("Cannot convert value of type ${body.javaClass.name} to response body")
        }
    }
}

