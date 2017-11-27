package io.github.cjkent.osiris.core

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Test
class ModelTest {

    class Components : ComponentsProvider

    private val req = Request(HttpMethod.GET, "not used", Params(), Params(), Params(), Params(), null)
    private val comps = Components()

    fun createSimpleSubRoute() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route = LambdaRoute(HttpMethod.GET, "/foo/bar", handler)
        val subRoute = SubRoute(route)
        assertEquals(subRoute.segments, listOf(FixedSegment("foo"), FixedSegment("bar")))
    }

    fun createVariableSubRoute() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route = LambdaRoute(HttpMethod.GET, "/foo/{bar}/baz", handler)
        val subRoute = SubRoute(route)
        assertEquals(subRoute.segments, listOf(FixedSegment("foo"), VariableSegment("bar"), FixedSegment("baz")))
    }

    fun createSimpleRouteNode() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/foo", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/foo/bar", handler2)
        val rootNode = RouteNode.create(route1, route2)

        assertEquals("", rootNode.name)
        assertNull(rootNode.variableChild)
        assertEquals(rootNode.handlers.size, 0)

        assertEquals(setOf("foo"), rootNode.fixedChildren.keys)
        val fooNode = rootNode.fixedChildren["foo"]!!
        assertEquals(setOf(HttpMethod.GET), fooNode.handlers.keys)
        val (fooHandler, fooAuth) = fooNode.handlers[HttpMethod.GET]!!
        assertEquals("1", fooHandler(comps, req).body)
        assertEquals(NoAuth, fooAuth)

        assertEquals(setOf("bar"), fooNode.fixedChildren.keys)
        val barNode = fooNode.fixedChildren["bar"]!!
        assertEquals(setOf(HttpMethod.POST), barNode.handlers.keys)
        val (barHandler, barAuth) = barNode.handlers[HttpMethod.POST]!!
        assertEquals("2", barHandler(comps, req).body)
        assertEquals(NoAuth, barAuth)
    }

    fun createVariableRouteNode() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.POST, "/{bar}", handler)
        val rootNode = RouteNode.create(route)
        assertTrue(rootNode.fixedChildren.isEmpty())
        assertEquals("bar", rootNode.variableChild?.name)
        assertEquals("1", rootNode.variableChild?.handlers?.get(HttpMethod.POST)!!.first(comps, req).body)
    }

    fun createRouteNodeWithDuplicateRoutesDifferentMethods() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route1 = LambdaRoute(HttpMethod.GET, "/foo", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/foo", handler2)
        RouteNode.create(route1, route2)
    }

    fun createRouteNodeWithDuplicateVariableRoutesDifferentMethods() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("2") }
        val route1 = LambdaRoute(HttpMethod.GET, "/{foo}", handler1)
        val route2 = LambdaRoute(HttpMethod.POST, "/{foo}", handler2)
        val rootNode = RouteNode.create(route1, route2)
        assertNotNull(rootNode.variableChild)
        val variableChild = rootNode.variableChild!!
        assertEquals("foo", variableChild.name)
        assertEquals(setOf(HttpMethod.GET, HttpMethod.POST), variableChild.handlers.keys)
        assertEquals("1", variableChild.handlers[HttpMethod.GET]!!.first(comps, req).body)
        assertEquals("2", variableChild.handlers[HttpMethod.POST]!!.first(comps, req).body)
    }

    @Test(
        expectedExceptions = arrayOf(IllegalArgumentException::class),
        expectedExceptionsMessageRegExp = "Multiple routes with the same HTTP method.*")
    fun createRouteNodeWithDuplicateRoutes() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route1 = LambdaRoute(HttpMethod.GET, "/foo", handler1)
        val route2 = LambdaRoute(HttpMethod.GET, "/foo", handler2)
        RouteNode.create(route1, route2)
    }

    @Test(
        expectedExceptions = arrayOf(IllegalArgumentException::class),
        expectedExceptionsMessageRegExp = "Routes found with clashing variable names.*")
    fun createRouteNodeWithNonMatchingVariableNames() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route1 = LambdaRoute(HttpMethod.GET, "/{foo}/bar", handler)
        val route2 = LambdaRoute(HttpMethod.GET, "/{bar}", handler)
        RouteNode.create(route1, route2)
    }

    fun createMultipleVariableRouteNodes() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route1 = LambdaRoute(HttpMethod.GET, "/{foo}/bar", handler)
        val route2 = LambdaRoute(HttpMethod.GET, "/{foo}", handler)
        RouteNode.create(route1, route2)
    }

    fun createRootRouteNode() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.GET, "/", handler)
        val rootNode = RouteNode.create(route)
        assertEquals("", rootNode.name)
        assertNull(rootNode.variableChild)
        assertEquals(rootNode.handlers.size, 1)
        assertEquals(setOf(HttpMethod.GET), rootNode.handlers.keys)
        val (rootHandler, rootAuth) = rootNode.handlers[HttpMethod.GET]!!
        assertTrue(rootNode is FixedRouteNode)
        assertEquals("", rootNode.name)
        assertEquals("1", rootHandler(comps, req).body)
        assertEquals(NoAuth, rootAuth)
    }
}
