package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Test
class MatchTest {

    class Components : ApiComponents

    fun fixedRoute() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/foo", handler)
        val node = RouteNode.create(route)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertNull(node.match(HttpMethod.GET, "/bar"))
        assertNull(node.match(HttpMethod.POST, "/foo"))
        assertEquals(RouteMatch(route.handler, mapOf()), node.match(HttpMethod.GET, "/foo"))
    }

    fun fixedRouteMultipleMethods() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/foo", handler1)
        val route2 = Route(HttpMethod.POST, "/foo", handler2)
        val node = RouteNode.create(route1, route2)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertEquals(RouteMatch(route1.handler, mapOf()), node.match(HttpMethod.GET, "/foo"))
        assertEquals(RouteMatch(route2.handler, mapOf()), node.match(HttpMethod.POST, "/foo"))
    }

    fun multipleFixedRoutes() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/foo", handler1)
        val route2 = Route(HttpMethod.POST, "/bar/baz", handler2)
        val node = RouteNode.create(route1, route2)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertNull(node.match(HttpMethod.GET, "/foo/baz"))
        assertEquals(RouteMatch(route1.handler, mapOf()), node.match(HttpMethod.GET, "/foo"))
        assertEquals(RouteMatch(route2.handler, mapOf()), node.match(HttpMethod.POST, "/bar/baz"))
    }

    fun variableRoute() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/{foo}", handler)
        val node = RouteNode.create(route)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertNull(node.match(HttpMethod.POST, "/bar"))
        assertEquals(RouteMatch(route.handler, mapOf("foo" to "bar")), node.match(HttpMethod.GET, "/bar"))
    }

    fun variableRouteMultipleMethods() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/{foo}", handler1)
        val route2 = Route(HttpMethod.POST, "/{foo}", handler2)
        val node = RouteNode.create(route1, route2)
        assertNull(node.match(HttpMethod.GET, "/"))
        assertEquals(RouteMatch(route1.handler, mapOf("foo" to "bar")), node.match(HttpMethod.GET, "/bar"))
        assertEquals(RouteMatch(route2.handler, mapOf("foo" to "bar")), node.match(HttpMethod.POST, "/bar"))
    }

    fun variableRouteMultipleVariables() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/{foo}/bar/{baz}", handler)
        val node = RouteNode.create(route)
        assertEquals(RouteMatch(route.handler, mapOf("foo" to "abc", "baz" to "def")), node.match(HttpMethod.GET, "/abc/bar/def"))
    }

    fun multipleVariableRoutes() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/{foo}", handler1)
        val route2 = Route(HttpMethod.POST, "/{foo}/baz", handler2)
        val node = RouteNode.create(route1, route2)
        assertEquals(RouteMatch(route1.handler, mapOf("foo" to "bar")), node.match(HttpMethod.GET, "/bar"))
        assertEquals(RouteMatch(route2.handler, mapOf("foo" to "bar")), node.match(HttpMethod.POST, "/bar/baz"))
        assertNull(node.match(HttpMethod.POST, "/bar/qux"))
    }

    fun fixedTakesPriority() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/{foo}", handler1)
        val route2 = Route(HttpMethod.GET, "/foo", handler2)
        val node = RouteNode.create(route1, route2)
        assertEquals(RouteMatch(route2.handler, mapOf()), node.match(HttpMethod.GET, "/foo"))
    }

    fun handlerAtRoot() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/", handler)
        val node = RouteNode.create(route)
        assertNull(node.match(HttpMethod.GET, "/foo"))
        assertEquals(RouteMatch(route.handler, mapOf()), node.match(HttpMethod.GET, "/"))
    }
}
