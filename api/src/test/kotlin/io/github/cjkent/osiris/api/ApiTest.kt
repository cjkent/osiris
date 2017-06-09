package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertFailsWith

@Test
class ApiTest {

    fun pathPartPattern() {
        validatePathPart("/foo")
        validatePathPart("/{foo}")
        validatePathPart("/123~_-.()")
        assertFailsWith<IllegalArgumentException> { validatePathPart("foo") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/foo/") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/foo/bar") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/fo/o") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/fo{o") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/fo{o}") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/{fo{o}") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/{fo}o}") }
        assertFailsWith<IllegalArgumentException> { validatePathPart("/{fo}o") }
    }
}
