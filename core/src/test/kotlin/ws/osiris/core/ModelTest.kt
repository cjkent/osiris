package ws.osiris.core

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Test
class ModelTest {

    class Components : ComponentsProvider

    private val req = Request(HttpMethod.GET, "notUsed", Params(), Params(), Params(), Params(), null)
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
        assertEquals(rootNode.operations.size, 0)

        assertEquals(setOf("foo"), rootNode.fixedChildren.keys)
        val fooNode = rootNode.fixedChildren["foo"]!!
        assertEquals(setOf(HttpMethod.GET), fooNode.operations.keys)
        val fooOperation = fooNode.operations[HttpMethod.GET]!!
        assertEquals("1", fooOperation.requestHandler(comps, req).body)
        assertEquals(NoAuth, fooOperation.auth)

        assertEquals(setOf("bar"), fooNode.fixedChildren.keys)
        val barNode = fooNode.fixedChildren["bar"]!!
        assertEquals(setOf(HttpMethod.POST), barNode.operations.keys)
        val barOperation = barNode.operations[HttpMethod.POST]!!
        assertEquals("2", barOperation.requestHandler(comps, req).body)
        assertEquals(NoAuth, barOperation.auth)
    }

    fun createVariableRouteNode() {
        val handler: RequestHandler<Components> = { req -> req.responseBuilder().build("1") }
        val route = LambdaRoute(HttpMethod.POST, "/{bar}", handler)
        val rootNode = RouteNode.create(route)
        assertTrue(rootNode.fixedChildren.isEmpty())
        assertEquals("bar", rootNode.variableChild?.name)
        assertEquals("1", rootNode.variableChild?.operations?.get(HttpMethod.POST)!!.requestHandler(comps, req).body)
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
        assertEquals(setOf(HttpMethod.GET, HttpMethod.POST), variableChild.operations.keys)
        assertEquals("1", variableChild.operations[HttpMethod.GET]!!.requestHandler(comps, req).body)
        assertEquals("2", variableChild.operations[HttpMethod.POST]!!.requestHandler(comps, req).body)
    }

    @Test(
        expectedExceptions = [IllegalArgumentException::class],
        expectedExceptionsMessageRegExp = "Multiple routes with the same HTTP method.*")
    fun createRouteNodeWithDuplicateRoutes() {
        val handler1: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val handler2: RequestHandler<Components> = { req -> req.responseBuilder().build("") }
        val route1 = LambdaRoute(HttpMethod.GET, "/foo", handler1)
        val route2 = LambdaRoute(HttpMethod.GET, "/foo", handler2)
        RouteNode.create(route1, route2)
    }

    @Test(
        expectedExceptions = [IllegalArgumentException::class],
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
        assertEquals(rootNode.operations.size, 1)
        assertEquals(setOf(HttpMethod.GET), rootNode.operations.keys)
        val rootOperation = rootNode.operations[HttpMethod.GET]!!
        assertTrue(rootNode is FixedRouteNode)
        assertEquals("", rootNode.name)
        assertEquals("1", rootOperation.requestHandler(comps, req).body)
        assertEquals(NoAuth, rootOperation.auth)
    }
}
