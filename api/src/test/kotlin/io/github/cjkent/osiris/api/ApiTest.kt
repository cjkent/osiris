package io.github.cjkent.osiris.api

import org.testng.annotations.Test
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
}
