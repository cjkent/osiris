package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Test
class ModelTest {

    class Components : ApiComponents

    fun createSimpleSubRoute() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/foo/bar", handler)
        val subRoute = SubRoute(route)
        assertEquals(subRoute.segments, listOf(FixedSegment("foo"), FixedSegment("bar")))
    }

    fun createVariableSubRoute() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/foo/{bar}/baz", handler)
        val subRoute = SubRoute(route)
        assertEquals(subRoute.segments, listOf(FixedSegment("foo"), VariableSegment("bar"), FixedSegment("baz")))
    }

    fun createSimpleRouteNode() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/foo", handler1)
        val route2 = Route(HttpMethod.POST, "/foo/bar", handler2)
        val rootNode = RouteNode.create(route1, route2)

        assertEquals(FixedSegment(""), rootNode.segment)
        assertNull(rootNode.variableChild)
        assertEquals(rootNode.routes.size, 0)

        assertEquals(setOf("foo"), rootNode.fixedChildren.keys)
        val fooNode = rootNode.fixedChildren["foo"]!!
        assertEquals(setOf(HttpMethod.GET), fooNode.routes.keys)
        val fooRoute = fooNode.routes[HttpMethod.GET]!!
        assertEquals(handler1, fooRoute.handler)
        assertEquals("/foo", fooRoute.path)

        assertEquals(setOf("bar"), fooNode.fixedChildren.keys)
        val barNode = fooNode.fixedChildren["bar"]!!
        assertEquals(setOf(HttpMethod.POST), barNode.routes.keys)
        val barRoute = barNode.routes[HttpMethod.POST]!!
        assertEquals(handler2, barRoute.handler)
        assertEquals("/foo/bar", barRoute.path)
    }

    fun createVariableRouteNode() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.POST, "/{bar}", handler)
        val rootNode = RouteNode.create(route)
        assertTrue(rootNode.fixedChildren.isEmpty())
        assertEquals(VariableSegment("bar"), rootNode.variableChild?.segment)
        assertEquals(handler, rootNode.variableChild?.routes?.get(HttpMethod.POST)?.handler)
    }

    fun createRouteNodeWithDuplicateRoutesDifferentMethods() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/foo", handler1)
        val route2 = Route(HttpMethod.POST, "/foo", handler2)
        RouteNode.create(route1, route2)
    }

    fun createRouteNodeWithDuplicateVariableRoutesDifferentMethods() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/{foo}", handler1)
        val route2 = Route(HttpMethod.POST, "/{foo}", handler2)
        val rootNode = RouteNode.create(route1, route2)
        assertNotNull(rootNode.variableChild)
        val variableChild = rootNode.variableChild!!
        assertEquals(setOf(HttpMethod.GET, HttpMethod.POST), variableChild.routes.keys)

        assertEquals(handler1, variableChild.routes[HttpMethod.GET]?.handler)
        assertEquals("/{foo}", variableChild.routes[HttpMethod.GET]?.path)

        assertEquals(handler2, variableChild.routes[HttpMethod.POST]?.handler)
        assertEquals("/{foo}", variableChild.routes[HttpMethod.POST]?.path)
    }

    @Test(
        expectedExceptions = arrayOf(IllegalArgumentException::class),
        expectedExceptionsMessageRegExp = "Multiple routes with the same HTTP method.*")
    fun createRouteNodeWithDuplicateRoutes() {
        val handler1: Handler<Components> = { _ -> "" }
        val handler2: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/foo", handler1)
        val route2 = Route(HttpMethod.GET, "/foo", handler2)
        RouteNode.create(route1, route2)
    }

    @Test(
        expectedExceptions = arrayOf(IllegalArgumentException::class),
        expectedExceptionsMessageRegExp = "Routes found with clashing variable names.*")
    fun createRouteNodeWithNonMatchingVariableNames() {
        val handler: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/{foo}/bar", handler)
        val route2 = Route(HttpMethod.GET, "/{bar}", handler)
        RouteNode.create(route1, route2)
    }

    fun createMultipleVariableRouteNodes() {
        val handler: Handler<Components> = { _ -> "" }
        val route1 = Route(HttpMethod.GET, "/{foo}/bar", handler)
        val route2 = Route(HttpMethod.GET, "/{foo}", handler)
        RouteNode.create(route1, route2)
    }

    fun createRootRouteNode() {
        val handler: Handler<Components> = { _ -> "" }
        val route = Route(HttpMethod.GET, "/", handler)
        val rootNode = RouteNode.create(route)
        assertEquals(FixedSegment(""), rootNode.segment)
        assertNull(rootNode.variableChild)
        assertEquals(rootNode.routes.size, 1)
        assertEquals(setOf(HttpMethod.GET), rootNode.routes.keys)
        val rootRoute = rootNode.routes[HttpMethod.GET]!!
        assertEquals(handler, rootRoute.handler)
        assertEquals("/", rootRoute.path)
    }
}
