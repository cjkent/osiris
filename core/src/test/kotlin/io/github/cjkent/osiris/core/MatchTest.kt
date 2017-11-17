package io.github.cjkent.osiris.core

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Test
class MatchTest {

    class Components : ComponentsProvider

    private val req = Request(HttpMethod.GET, "not used", Params(), Params(), Params(), Params(), null)
    private val comps = Components()

    fun fixedRoute() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.GET, "/foo", handler)
        val node = RouteNode.create(route)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertNull(node.match(HttpMethod.GET, "/bar"))
        assertNull(node.match(HttpMethod.POST, "/foo"))
        assertEquals("1", node.match(HttpMethod.GET, "/foo")!!.handler(comps, req).body)
    }

    fun fixedRouteMultipleMethods() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/foo", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/foo", handler2)
        val node = RouteNode.create(route1, route2)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertEquals("1", node.match(HttpMethod.GET, "/foo")!!.handler(comps, req).body)
        assertEquals("2", node.match(HttpMethod.POST, "/foo")!!.handler(comps, req).body)
    }

    fun multipleFixedRoutes() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/foo", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/bar/baz", handler2)
        val node = RouteNode.create(route1, route2)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertNull(node.match(HttpMethod.GET, "/foo/baz"))
        assertEquals("1", node.match(HttpMethod.GET, "/foo")!!.handler(comps, req).body)
        assertEquals("2", node.match(HttpMethod.POST, "/bar/baz")!!.handler(comps, req).body)
    }

    fun variableRoute() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.GET, "/{foo}", handler)
        val node = RouteNode.create(route)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertNull(node.match(HttpMethod.POST, "/bar"))
        val match = node.match(HttpMethod.GET, "/bar")
        val response = match!!.handler(comps, req)
        assertEquals("1", response.body)
        assertEquals(mapOf("foo" to "bar"), match.vars)
    }

    fun variableRouteMultipleMethods() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/{foo}", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/{foo}", handler2)
        val node = RouteNode.create(route1, route2)
        assertNull(node.match(HttpMethod.GET, "/"))
        val match1 = node.match(HttpMethod.GET, "/bar")
        val response1 = match1!!.handler(comps, req)
        assertEquals("1", response1.body)
        assertEquals(mapOf("foo" to "bar"), match1.vars)
        val match2 = node.match(HttpMethod.POST, "/bar")
        val response2 = match2!!.handler(comps, req)
        assertEquals("2", response2.body)
        assertEquals(mapOf("foo" to "bar"), match2.vars)
    }

    fun variableRouteMultipleVariables() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.GET, "/{foo}/bar/{baz}", handler)
        val node = RouteNode.create(route)
        val match = node.match(HttpMethod.GET, "/abc/bar/def")
        val response = match!!.handler(comps, req)
        assertEquals(mapOf("foo" to "abc", "baz" to "def"), match.vars)
        assertEquals("1", response.body)
    }

    fun multipleVariableRoutes() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/{foo}", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/{foo}/baz", handler2)
        val node = RouteNode.create(route1, route2)
        val match1 = node.match(HttpMethod.GET, "/bar")
        val response1 = match1!!.handler(comps, req)
        assertEquals("1", response1.body)
        assertEquals(mapOf("foo" to "bar"), match1.vars)
        val match2 = node.match(HttpMethod.POST, "/bar/baz")
        val response2 = match2!!.handler(comps, req)
        assertEquals("2", response2.body)
        assertEquals(mapOf("foo" to "bar"), match2.vars)
        assertNull(node.match(HttpMethod.POST, "/bar/qux"))
    }

    fun fixedTakesPriority() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/{foo}", handler1)
        val route2 = LambdaRoute(HttpMethod.GET, "/foo", handler2)
        val node = RouteNode.create(route1, route2)
        assertEquals("2", node.match(HttpMethod.GET, "/foo")!!.handler(comps, req).body)
    }

    fun handlerAtRoot() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.GET, "/", handler)
        val node = RouteNode.create(route)
        assertNull(node.match(HttpMethod.GET, "/foo"))
        assertEquals("1", node.match(HttpMethod.GET, "/")!!.handler(comps, req).body)
    }
}
