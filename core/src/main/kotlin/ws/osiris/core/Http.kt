package ws.osiris.core

import ws.osiris.core.ContentType.Companion.parse
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import kotlin.reflect.KClass

/**
 * A set of HTTP parameters provided as part of request; can represent headers, path parameters or
 * query string parameters.
 *
 * Parameter lookup is case-insensitive in accordance with the HTTP spec. For example, if a
 * request contains the header "Content-Type" then the value will be returned when looked up as follows:
 *
 *     val contentType = req.headers["content-type"]
 *
 * This class does not support repeated values in query strings as API Gateway doesn't support them.
 * For example, a query string of `foo=123&foo=456` will only contain one value for `foo`. It is
 * undefined which one.
 */
class Params(params: Map<String, String>?) {

    constructor() : this(mapOf())

    val params: Map<String, String> = params ?: mapOf()

    private val lookupParams = this.params.mapKeys { (key, _) -> key.lowercase(Locale.ENGLISH) }

    /** Returns the named parameter or throws `IllegalArgumentException` if there is no parameter with the name. */
    operator fun get(name: String): String = optional(name) ?: throw IllegalArgumentException("No value named '$name'")

    /**
     * Returns copy of these parameters with the value added.
     *
     * The value overwrites an existing parameter with the same name.
     */
    operator fun plus(nameValue: Pair<String, String>) = Params(params + nameValue)

    /** Returns copy of these parameters with the named parameter removed. */
    operator fun minus(name: String) = Params(params - name)

    /** Returns the named parameter. */
    fun optional(name: String): String? = lookupParams[name.lowercase(Locale.ENGLISH)]

    companion object {

        /** Creates a set of parameters by parsing an HTTP query string. */
        fun fromQueryString(queryString: String?): Params = when {
            queryString == null || queryString.trim().isEmpty() -> Params(mapOf())
            else -> Params(URLDecoder.decode(queryString, "UTF-8").split("&").map { splitVar(it) }.toMap())
        }

        private fun splitVar(nameAndValue: String): Pair<String, String> {
            return when (val index = nameAndValue.indexOf('=')) {
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
    val context: Params,
    val body: Any? = null,
    val attributes: Map<String, Any> = mapOf(),
    val defaultResponseHeaders: Map<String, String> = mapOf()
) {

    internal val requestPath: RequestPath = RequestPath(path)

    /** Returns the body or throws `IllegalArgumentException` if it is null. */
    @Deprecated("Use body<Any>()", replaceWith = ReplaceWith("body<Any>()"))
    fun requireBody(): Any = body ?: throw IllegalArgumentException("Request body is required")

    /** Returns the body or throws `IllegalArgumentException` if it is null or not of the expected type. */
    @Suppress("UNCHECKED_CAST")
    @Deprecated("Use body<T>()", replaceWith = ReplaceWith("body<T>()"))
    fun <T : Any> requireBody(expectedType: KClass<T>): T = when {
        body == null -> throw IllegalArgumentException("Request body is required")
        !expectedType.java.isInstance(body) -> throw IllegalArgumentException("Request body is not of the expected type")
        else -> body as T
    }

    /** Returns the body or throws `IllegalArgumentException` if it is null or not of the expected type. */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> body(): T = when {
        body == null -> throw IllegalArgumentException("Request body is required")
        !T::class.java.isInstance(body) -> throw IllegalArgumentException("Request body is not of the expected type")
        else -> body as T
    }

    /** Returns the body as a byte array or throws `IllegalArgumentException` if there is no body or it isn't binary. */
    fun requireBinaryBody(): ByteArray = when (body) {
        null -> throw IllegalArgumentException("Request body is required")
        is ByteArray -> body
        else -> throw IllegalArgumentException("Request body is not binary")
    }

    /**
     * Returns a builder for building a response.
     *
     * This is used to customise the headers or the status of the response.
     */
    fun responseBuilder(): ResponseBuilder =
        ResponseBuilder(defaultResponseHeaders.toMutableMap())

    /**
     * Returns the named attribute with the specified type.
     *
     * @throws IllegalStateException if there is no attribute with the specified name of if an attribute is
     * found with the wrong type
     */
    inline fun <reified T> attribute(name: String): T {
        val attribute = attributes[name] ?: throw IllegalStateException("No attribute found with name '$name'")
        if (attribute !is T) {
            throw IllegalStateException("Attribute '$name' does not have expected type. " +
                "Expected ${T::class.java.name}, found ${attribute.javaClass.name}")
        }
        return attribute
    }

    /**
     * Returns a copy of this request with the value added to its attributes, keyed by the name.
     */
    fun withAttribute(name: String, value: Any): Request = copy(attributes = attributes + (name to value))
}

/**
 * Standard HTTP header names.
 */
object HttpHeaders {
    const val ACCEPT = "Accept"
    const val AUTHORIZATION = "Authorization"
    const val CONTENT_TYPE = "Content-Type"
    const val CONTENT_LENGTH = "Content-Length"
    const val LOCATION = "Location"
}

/**
 * Standard MIME types.
 */
object MimeTypes {
    const val APPLICATION_JSON = "application/json"
    const val APPLICATION_XML = "application/xml"
    const val APPLICATION_XHTML = "application/xhtml+xml"
    const val TEXT_HTML = "text/html"
    const val TEXT_PLAIN = "text/plain"
}

/** The default content type in API Gateway; everything is assumed to return JSON unless it states otherwise. */
val JSON_CONTENT_TYPE = ContentType(MimeTypes.APPLICATION_JSON)

// It might have been nicer to make this a sealed type with subtypes for text and binary content types.
// Charset is only relevant for text types and boundary is only relevant for multipart form data.

/**
 * Represents the data in a `Content-Type` header; includes the MIME type and an optional charset.
 *
 * The [parse] function parses a `Content-Type` header and creates a [ContentType] instance.
 */
data class ContentType(
    /** The MIME type of the content. */
    val mimeType: String,
    /** The charset of the content. */
    val charset: Charset?,
    /** The boundary between fields in `multipart/form-data`. */
    val boundary: String? = null
) {

    /** The string representation of this content type used in a `Content-Type` header. */
    val header: String

    init {
        if (this.mimeType.isBlank()) throw IllegalArgumentException("MIME type cannot be blank")
        header = if (charset == null && boundary == null) {
            mimeType.trim()
        } else if (charset != null) {
            "${mimeType.trim()}; charset=${charset.name()}"
        } else {
            "${mimeType.trim()}; boundary=$boundary"
        }
    }

    /** Creates an instance with the specified MIME type and no charset or boundary. */
    constructor(mimeType: String) : this(mimeType, null)

    companion object {

        private val REGEX = Regex("""\s*(?<type>\S+?)\s*(;\s*charset=(?<charset>\S+)\s*)?""", RegexOption.IGNORE_CASE)
        private val MULTIPART_FORM_REGEX = Regex("""\s*multipart/form-data\s*;\s*boundary=(?<boundary>\S{1,70})\s*""", RegexOption.IGNORE_CASE)

        /**
         * Parses a `Content-Type` header into a [ContentType] instance.
         */
        fun parse(header: String): ContentType {
            val multipartResult = MULTIPART_FORM_REGEX.matchEntire(header)
            if (multipartResult != null) {
                val boundary = multipartResult.groups["boundary"]?.value
                return ContentType("multipart/form-data", null, boundary)
            }
            val matchResult = REGEX.matchEntire(header) ?: throw IllegalArgumentException("Invalid Content-Type")
            val mimeType = matchResult.groups["type"]?.value!!
            val charset = matchResult.groups["charset"]?.let { Charset.forName(it.value) }
            return ContentType(mimeType, charset)
        }
    }
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

    private var status: Int = 200

    /** Sets the value of the named header and returns this builder. */
    fun header(name: String, value: String): ResponseBuilder {
        headers[name] = value
        return this
    }

    /** Sets the status code of the response and returns this builder. */
    fun status(status: Int): ResponseBuilder {
        this.status = status
        return this
    }

    /** Builds a response from the data in this builder. */
    fun build(body: Any? = null): Response = Response(status, Headers(headers), body)
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

    /**
     * Returns a copy of these headers with a new header added.
     *
     * If this object already contains the header it will be replaced by the new value.
     */
    fun withHeader(header: String, value: String): Response = copy(headers = headers + (header to value))

    /**
     * Returns a copy of these headers with new headers added.
     *
     * If this object already contains the headers they will be replaced by the new values.
     */
    fun withHeaders(vararg headerValue: Pair<String, String>): Response = copy(headers = headers + headerValue.toMap())

    /**
     * Returns a copy of these headers with new headers added.
     *
     * If this object already contains the headers they will be replaced by the new values.
     */
    fun withHeaders(headersMap: Map<String, String>): Response = copy(headers = headers + headersMap)

    /**
     * Returns a copy of these headers with new headers added.
     *
     * If this object already contains the headers they will be replaced by the new values.
     */
    fun withHeaders(headers: Headers): Response = copy(headers = this.headers + headers)

    companion object {

        /**
         * Returns an error response with content type `text/plain` and the specified [status] and
         * with [message] as the request body.
         */
        internal fun error(status: Int, message: String?): Response {
            val headers = mapOf(HttpHeaders.CONTENT_TYPE to MimeTypes.TEXT_PLAIN)
            return Response(status, Headers(headers), message)
        }
    }
}

/** A map of HTTP headers that looks up values in a case-insensitive fashion (in accordance with the HTTP spec). */
data class Headers(val headerMap: Map<String, String> = mapOf()) {

    constructor(vararg headers: Pair<String, String>) : this(headers.toMap())

    private val lookupMap = headerMap.mapKeys { (key, _) -> key.lowercase(Locale.ENGLISH) }

    /**
     * Returns the value for the specified [header]; lookup is case-insensitive in accordance with the HTTP spec.
     */
    operator fun get(header: String): String? = lookupMap[header.lowercase(Locale.ENGLISH)]

    /**
     * Returns a copy of these headers with a new header added.
     *
     * If this object already contains the header it will be replaced by the new value.
     */
    operator fun plus(headerValue: Pair<String, String>): Headers = withHeader(headerValue.first, headerValue.second)

    /**
     * Returns a copy of these headers with new headers added.
     *
     * If this object already contains the headers they will be replaced by the new values.
     */
    operator fun plus(other: Headers): Headers = Headers(headerMap + other.headerMap)

    /**
     * Returns a copy of these headers with new headers added.
     *
     * If this object already contains the headers they will be replaced by the new values.
     */
    operator fun plus(other: Map<String, String>): Headers = Headers(headerMap + other)

    /**
     * Returns a copy of these headers with a new header added.
     *
     * If this object already contains the header it will be replaced by the new value.
     */
    fun withHeader(header: String, value: String): Headers = Headers(headerMap + (header to value))

    /**
     * Returns a copy of these headers with new headers added.
     *
     * If this object already contains the headers they will be replaced by the new values.
     */
    fun withHeaders(vararg headers: Pair<String, String>): Headers = Headers(headerMap + headers.toMap())
}

enum class HttpMethod {
    GET,
    POST,
    PUT,
    UPDATE,
    OPTIONS,
    PATCH,
    DELETE
}

/**
 * Creates a [Params] instance representing the request context; only used in testing.
 *
 * When an Osiris application is deployed on AWS then the request context is filled in by API Gateway. In some
 * cases the handler code uses the context, for example to get the name of the API Gateway stage. When the
 * application is running in a local server the context information needed by the handler code must still be
 * provided.
 *
 * This interface provides a way for the user to plug-in something to generate valid context information when
 * the code is running on a regular HTTP server.
 *
 * There are two simple implementations provided
 *
 * This is only needed in testing, but needs to be in the `core` module so it can be used in the core tests
 * and in the main HTTP server code in `local-server`.
 */
interface RequestContextFactory {

    fun createContext(
        httpMethod: HttpMethod,
        path: String,
        headers: Params,
        queryParams: Params,
        pathParams: Params,
        body: Any?
    ): Params

    companion object {

        /** Returns a factory that returns an empty context; used by default if no other factory is provided.  */
        fun empty(): RequestContextFactory = EmptyRequestContextFactory()

        /** Returns a factory that returns the same context every time, built from `values`. */
        fun fixed(vararg values: Pair<String, String>): RequestContextFactory = FixedRequestContextFactory(values.toMap())
    }
}

private class EmptyRequestContextFactory : RequestContextFactory {

    private val context: Params = Params()

    override fun createContext(
        httpMethod: HttpMethod,
        path: String,
        headers: Params,
        queryParams: Params,
        pathParams: Params,
        body: Any?
    ): Params = context

}

private class FixedRequestContextFactory(values: Map<String, String>) : RequestContextFactory {

    private val context = Params(values)

    override fun createContext(
        httpMethod: HttpMethod,
        path: String,
        headers: Params,
        queryParams: Params,
        pathParams: Params,
        body: Any?
    ): Params = context
}
