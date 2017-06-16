package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Test
class FilterTest {

    fun simpleFilter() {
        // Filter that changes the response content type to XML by modifying the request and converts the response
        // body to upper case by modifying the response
        val filterHandler: FilterHandler<ApiComponents> = { req, handler ->
            val newReq = req.copy(
                defaultResponseHeaders = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.APPLICATION_XML)
            )
            val response = handler(this, newReq)
            response.copy(body = (response.body as String).toUpperCase())
        }
        val filter = Filter("/*", filterHandler)
        val handler1: RequestHandler<ApiComponents> = { req -> req.responseBuilder().build("root") }
        val handler2: RequestHandler<ApiComponents> = { req -> req.responseBuilder().build("foo") }
        val route1 = Route(HttpMethod.GET, "/", handler1).wrap(listOf(filter))
        val route2 = Route(HttpMethod.GET, "/foo", handler2).wrap(listOf(filter))
        val components = object : ApiComponents {}

        val req1 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response1 = route1.handler(components, req1)
        assertEquals("ROOT", response1.body)
        assertEquals(ContentTypes.APPLICATION_XML, response1.headers[HttpHeaders.CONTENT_TYPE])

        val req2 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response2 = route2.handler(components, req2)
        assertEquals("FOO", response2.body)
        assertEquals(ContentTypes.APPLICATION_XML, response2.headers[HttpHeaders.CONTENT_TYPE])
    }

    fun multipleFilters() {
        val filterHandler1: FilterHandler<ApiComponents> = { req, handler ->
            val newReq = req.copy(
                defaultResponseHeaders = mapOf("foo" to "1")
            )
            val response = handler(this, newReq)
            response.copy(body = response.body.toString() + "1")
        }
        val filterHandler2: FilterHandler<ApiComponents> = { req, handler ->
            val fooHeader = req.defaultResponseHeaders["foo"]!!
            val newReq = req.copy(
                defaultResponseHeaders = mapOf("foo" to fooHeader + "2")
            )
            val response = handler(this, newReq)
            response.copy(body = response.body.toString() + "2")
        }
        val filter1 = Filter("/*", filterHandler1)
        val filter2 = Filter("/*", filterHandler2)
        val filters = listOf(filter1, filter2)
        val handler1: RequestHandler<ApiComponents> = { req -> req.responseBuilder().build("root") }
        val handler2: RequestHandler<ApiComponents> = { req -> req.responseBuilder().build("foo") }
        val route1 = Route(HttpMethod.GET, "/", handler1).wrap(filters)
        val route2 = Route(HttpMethod.GET, "/foo", handler2).wrap(filters)

        val components = object : ApiComponents {}

        val req1 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response1 = route1.handler(components, req1)
        assertEquals("12", response1.headers["foo"])
        assertEquals("root21", response1.body)

        val req2 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response2 = route2.handler(components, req2)
        assertEquals("12", response1.headers["foo"])
        assertEquals("foo21", response2.body)
    }

    fun filterInApi() {
        val client = InMemoryTestClient.create {
            filter { req, handler ->
                val newReq = req.copy(
                    defaultResponseHeaders = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.APPLICATION_XML)
                )
                val response = handler(this, newReq)
                response.copy(body = (response.body as String).toUpperCase())

            }
            get("/foo") { _ ->
                "foo"
            }
        }
        val response = client.get("/foo")
        assertEquals("FOO", response.body)
        assertEquals(ContentTypes.APPLICATION_XML, response.headers[HttpHeaders.CONTENT_TYPE])
    }

    fun matchExact() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        val filter = Filter("/foo/bar", handler)
        assertTrue(filter.matches(listOf("foo", "bar")))
        assertFalse(filter.matches(listOf("foo", "baz")))
        assertFalse(filter.matches(listOf("foo")))
        assertFalse(filter.matches(listOf()))
    }

    fun matchWildcard() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        val filter = Filter("/foo/*", handler)
        assertTrue(filter.matches(listOf("foo")))
        assertTrue(filter.matches(listOf("foo", "bar")))
        assertTrue(filter.matches(listOf("foo", "bar", "baz")))
        assertFalse(filter.matches(listOf("bar")))
        assertFalse(filter.matches(listOf()))
    }

    fun matchInternalWildcard() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        val filter = Filter("/foo/*/bar", handler)
        assertTrue(filter.matches(listOf("foo", "baz", "bar")))
        assertFalse(filter.matches(listOf("foo")))
        assertFalse(filter.matches(listOf("foo", "bar")))
        assertFalse(filter.matches(listOf()))
    }

    fun matchWildcardAtRoot() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        val filter = Filter("/*", handler)
        assertTrue(filter.matches(listOf()))
        assertTrue(filter.matches(listOf("foo")))
        assertTrue(filter.matches(listOf("foo", "bar")))
    }

    fun matchPathVar() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        val filter = Filter("/foo/{qux}", "/*", handler)
        assertTrue(filter.matches(listOf("foo", "bar")))
        assertTrue(filter.matches(listOf("foo", "bar", "baz")))
        assertFalse(filter.matches(listOf("bar")))
        assertFalse(filter.matches(listOf()))
    }

    fun matchInternalPathVar() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        val filter = Filter("/foo/{abc}", "/bar", handler)
        assertTrue(filter.matches(listOf("foo", "baz", "bar")))
        assertFalse(filter.matches(listOf("foo")))
        assertFalse(filter.matches(listOf("foo", "bar")))
        assertFalse(filter.matches(listOf()))
    }

    fun matchInApi() {
        val api = api(ApiComponents::class) {
            filter { _, _ ->
                ""
            }
            filter("/foo") { _, _ ->
                ""
            }
            filter("/bar/*/baz") { _, _ ->
                ""
            }
            path("/qux/abc") {
                filter { _, _ ->
                    ""
                }
                filter("/foo") { _, _ ->
                    ""
                }
            }
            path("/xyz/{abc}/foo") {
                filter { _, _ ->
                    ""
                }
                filter("/bar") { _, _ ->
                    ""
                }
                filter("/bar/*") { _, _ ->
                    ""
                }
            }
        }
        assertTrue(api.filters[0].matches(listOf()))
        assertTrue(api.filters[0].matches(listOf("foo")))
        assertTrue(api.filters[0].matches(listOf("foo", "bar")))

        assertFalse(api.filters[1].matches(listOf()))
        assertTrue(api.filters[1].matches(listOf("foo")))
        assertFalse(api.filters[1].matches(listOf("foo", "bar")))

        assertFalse(api.filters[2].matches(listOf()))
        assertFalse(api.filters[2].matches(listOf("bar")))
        assertFalse(api.filters[2].matches(listOf("bar", "foo")))
        assertTrue(api.filters[2].matches(listOf("bar", "foo", "baz")))
        assertFalse(api.filters[2].matches(listOf("bar", "foo", "baz", "abc")))

        assertFalse(api.filters[3].matches(listOf("qux")))
        assertTrue(api.filters[3].matches(listOf("qux", "abc")))
        assertTrue(api.filters[3].matches(listOf("qux", "abc", "foo")))
        assertTrue(api.filters[3].matches(listOf("qux", "abc", "foo", "bar")))

        assertFalse(api.filters[4].matches(listOf("qux", "abc")))
        assertTrue(api.filters[4].matches(listOf("qux", "abc", "foo")))
        assertFalse(api.filters[4].matches(listOf("qux", "abc", "foo", "bar")))

        assertTrue(api.filters[5].matches(listOf("xyz", "123", "foo")))
        assertTrue(api.filters[5].matches(listOf("xyz", "123", "foo", "bar")))

        assertFalse(api.filters[6].matches(listOf("xyz", "123", "foo")))
        assertTrue(api.filters[6].matches(listOf("xyz", "123", "foo", "bar")))
        assertFalse(api.filters[6].matches(listOf("xyz", "123", "foo", "bar", "baz")))

        assertTrue(api.filters[7].matches(listOf("xyz", "123", "foo", "bar")))
        assertTrue(api.filters[7].matches(listOf("xyz", "123", "foo", "bar", "baz")))
    }

    fun pathValidation() {
        val handler: FilterHandler<ApiComponents> = { _, _ -> "" }
        assertFailsWith<IllegalArgumentException> { Filter("foo", "/bar", handler) }
        assertFailsWith<IllegalArgumentException> { Filter("/foo", "/{bar}", handler) }
    }

    fun applyFilterWithPath() {
        val client = InMemoryTestClient.create {
            filter("/foo") { req, handler ->
                handler(this, req).body.toString().toUpperCase()
            }
            get("/foo") { _ ->
                "foo"
            }
            get("/bar") { _ ->
                "bar"
            }
        }
        assertEquals("FOO", client.get("/foo").body as? String)
        assertEquals("bar", client.get("/bar").body as? String)
    }
}
