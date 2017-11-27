package io.github.cjkent.osiris.core

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Test
class RequestTest {

    fun withAttribute() {
        val request1 = Request(HttpMethod.GET, "/foo", Params(), Params(), Params(), Params())
        val request2 = request1.withAttribute("foo", "bar")
        val request3 = request2.withAttribute("baz", 123)
        assertEquals(mapOf(), request1.attributes)
        assertEquals(mapOf("foo" to "bar"), request2.attributes)
        assertEquals(mapOf("foo" to "bar", "baz" to 123), request3.attributes)
    }

    fun attribute() {
        val attrs = mapOf("foo" to "bar", "baz" to 123)
        val request = Request(HttpMethod.GET, "/foo", Params(), Params(), Params(), Params(), attributes = attrs)
        val foo = request.attribute<String>("foo")
        val baz = request.attribute<Int>("baz")
        assertEquals("bar", foo)
        assertEquals(123, baz)
        assertFailsWith(IllegalStateException::class) { request.attribute<Int>("foo") }
        assertFailsWith(IllegalStateException::class) { request.attribute<String>("baz") }
    }
}
