package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Test
class ApiTest {

    fun pathPattern() {
        Route.validatePath("/")
        Route.validatePath("/foo")
        Route.validatePath("/{foo}")
        Route.validatePath("/123~_-.()")
        Route.validatePath("/foo/bar")
        Route.validatePath("/foo/{bar}")
        Route.validatePath("/foo/{bar}/baz")
        assertFailsWith<IllegalArgumentException> { Route.validatePath("foo") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/foo/") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/fo{o") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/fo{o}") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/{fo{o}") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/{fo}o}") }
        assertFailsWith<IllegalArgumentException> { Route.validatePath("/{fo}o") }
    }

    /**
     * Tests creating a very simple API using the DSL, a components interface and components implementation.
     *
     * This is mostly to make sure I don't break the type signatures if I'm mucking around with the generics.
     */
    fun createApi() {
        val api = api(TestComponentsImpl::class) {
            get("/foo") {
                foo
            }
            get("/bar") {
                bar
            }
        }
        assertEquals(2, api.routes.size)
        assertEquals("/foo", api.routes[0].path)
        assertEquals("/bar", api.routes[1].path)
    }

    interface TestComponents : ApiComponents {
        val foo: String
        val bar: Int
    }

    class TestComponentsImpl(override val foo: String, override val bar: Int) : TestComponents
}
