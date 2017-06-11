package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals

@Test
class FilterTest {

    // TODO ATM the value returned from the handler isn't converted into a Response until the lambda / servlet
    // that means any changes to the response builder won't be visible to the filters
    // should the value returned by the first handler always be converted to a response?
    // by a filter? if so, should that filter be impossible to remove? or just do it with a fn? maybe less confusing

    fun simpleFilter() {
        val handler1: Handler<ApiComponents> = { req -> req.responseBuilder().build("root") }
        val route1 = Route(HttpMethod.GET, "/", handler1)
        val handler2: Handler<ApiComponents> = { req -> req.responseBuilder().build("foo") }
        val route2 = Route(HttpMethod.GET, "/foo", handler2)
        // Filter that changes the response content type to XML by modifying the request and converts the response
        // body to upper case by modifying the response
        val filterHandler: FilterHandler<ApiComponents> = { req, handler ->
            val newReq = req.copy(
                defaultResponseHeaders = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.APPLICATION_XML)
            )
            val handlerVal = handler(this, newReq)
            val response = handlerVal as Response
            response.copy(body = (response.body as String).toUpperCase())
        }
        val filter = Filter(filterHandler)
        val api = Api(listOf(route1, route2), listOf(filter), ApiComponents::class)
        val node = RouteNode.create(api)
        val components = object : ApiComponents {}

        val (matchHandler1, _) = node.match(HttpMethod.GET, "/")!!
        val req1 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val handlerVal1 = matchHandler1(components, req1)
        val response1 = handlerVal1 as Response
        assertEquals("ROOT", response1.body)
        assertEquals(ContentTypes.APPLICATION_XML, response1.headers[HttpHeaders.CONTENT_TYPE])

        val (matchHandler2, _) = node.match(HttpMethod.GET, "/foo")!!
        val req2 = Request(HttpMethod.GET, "/", Params(), Params(), Params(), null, false)
        val handlerVal2 = matchHandler2(components, req2)
        val response2 = handlerVal2 as Response
        assertEquals("FOO", response2.body)
        assertEquals(ContentTypes.APPLICATION_XML, response2.headers[HttpHeaders.CONTENT_TYPE])
    }
}
