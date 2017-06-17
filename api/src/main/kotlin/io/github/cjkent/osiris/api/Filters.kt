package io.github.cjkent.osiris.api

import kotlin.reflect.KClass

/**
 * Creates a filter that is applied to all endpoints.
 *
 * If a filter only applies to a subset of endpoints it should be defined as part of the API.
 */
fun <T : ApiComponents> defineFilter(
    componentsType: KClass<T>,
    handler: FilterHandler<T>
): Filter<T> = Filter("/*", handler)

// TODO is the subtyping going to work here?
/**
 * Filter that sets the default content type of the response to be `application/json`.
 *
 * This is done by changing the `defaultResponseHeaders` of the request. This is propagated to
 * the response headers via [Request.responseBuilder] function.
 */
val DEFAULT_CONTENT_TYPE_FILTER: Filter<ApiComponents> = defineFilter(ApiComponents::class) { req, handler ->
    val defaultHeaders = req.defaultResponseHeaders + (HttpHeaders.CONTENT_TYPE to ContentTypes.APPLICATION_JSON)
    val updatedReq = req.copy(defaultResponseHeaders = defaultHeaders)
    handler(this, updatedReq)
}

object StandardFilters {
    fun <T : ApiComponents> create(componentsType: KClass<T>): List<Filter<T>> {
        throw UnsupportedOperationException()
    }
}

val STANDARD_FILTERS: List<Filter<ApiComponents>> = listOf(DEFAULT_CONTENT_TYPE_FILTER)
