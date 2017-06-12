package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals

@Test
class FilterTest {

    fun simpleFilter() {
        val handler1: Handler<ApiComponents> = { req -> "root" }
        val route1 = Route(HttpMethod.GET, "/", handler1)
        val handler2: Handler<ApiComponents> = { req -> "foo" }
        val route2 = Route(HttpMethod.GET, "/foo", handler2)
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

        val api = Api(listOf(route1, route2), listOf(filter), ApiComponents::class)
        val node = RouteNode.create(api)
        val components = object : ApiComponents {}

        val (matchHandler1, _) = node.match(HttpMethod.GET, "/")!!
        val req1 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response1 = matchHandler1(components, req1)
        assertEquals("ROOT", response1.body)
        assertEquals(ContentTypes.APPLICATION_XML, response1.headers[HttpHeaders.CONTENT_TYPE])

        val (matchHandler2, _) = node.match(HttpMethod.GET, "/foo")!!
        val req2 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response2 = matchHandler2(components, req2)
        assertEquals("FOO", response2.body)
        assertEquals(ContentTypes.APPLICATION_XML, response2.headers[HttpHeaders.CONTENT_TYPE])
    }

    fun multipleFilters() {
        val handler1: Handler<ApiComponents> = { req -> "root" }
        val route1 = Route(HttpMethod.GET, "/", handler1)
        val handler2: Handler<ApiComponents> = { req -> "foo" }
        val route2 = Route(HttpMethod.GET, "/foo", handler2)
        val filterHandler1: FilterHandler<ApiComponents> = { req, handler ->
            val newReq = req.copy(
                defaultResponseHeaders = mapOf("foo" to "1")
            )
            val response = handler(this, newReq)
            response.copy(body = response.body.toString() + "1")
        }
        val filter1 = Filter("/*", filterHandler1)

        val filterHandler2: FilterHandler<ApiComponents> = { req, handler ->
            val fooHeader = req.defaultResponseHeaders["foo"]!!
            val newReq = req.copy(
                defaultResponseHeaders = mapOf("foo" to fooHeader + "2")
            )
            val response = handler(this, newReq)
            response.copy(body = response.body.toString() + "2")
        }
        val filter2 = Filter("/*", filterHandler2)

        val api = Api(listOf(route1, route2), listOf(filter1, filter2), ApiComponents::class)
        val node = RouteNode.create(api)
        val components = object : ApiComponents {}

        val (matchHandler1, _) = node.match(HttpMethod.GET, "/")!!
        val req1 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response1 = matchHandler1(components, req1)
        assertEquals("12", response1.headers["foo"])
        assertEquals("root21", response1.body)

        val (matchHandler2, _) = node.match(HttpMethod.GET, "/foo")!!
        val req2 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val response2 = matchHandler2(components, req2)
        assertEquals("12", response1.headers["foo"])
        assertEquals("foo21", response2.body)
    }

    fun filterInApi() {
        val api = api(ApiComponents::class) {
            filter { req, handler ->
                val newReq = req.copy(
                    defaultResponseHeaders = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.APPLICATION_XML)
                )
                val response = handler(this, newReq)
                response.copy(body = (response.body as String).toUpperCase())

            }
            get("/foo") { req ->
                "foo"
            }
        }
        val node = RouteNode.create(api)
        val (matchHandler, _) = node.match(HttpMethod.GET, "/foo")!!
        val req = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val components = object : ApiComponents {}
        val response = matchHandler(components, req)
        assertEquals("FOO", response.body)
        assertEquals(ContentTypes.APPLICATION_XML, response.headers[HttpHeaders.CONTENT_TYPE])
    }
}
