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

@Test
class ResponseTest {

    fun withHeader() {
        val response = Response(200, Headers("foo" to "123", "bar" to "345"), null)
        val expected = Response(200, Headers("foo" to "123", "bar" to "345", "baz" to "456"), null)
        assertEquals(expected, response.withHeader("baz", "456"))
    }

    fun withHeaders() {
        val response = Response(200, Headers("foo" to "123", "bar" to "345"), null)
        val expected = Response(200, Headers("foo" to "123", "bar" to "345", "baz" to "456", "qux" to "567"), null)
        assertEquals(expected, response.withHeaders("baz" to "456", "qux" to "567"))
    }

    fun withHeaders2() {
        val response = Response(200, Headers("foo" to "123", "bar" to "345"), null)
        val expected = Response(200, Headers("foo" to "123", "bar" to "345", "baz" to "456", "qux" to "567"), null)
        assertEquals(expected, response.withHeaders(Headers("baz" to "456", "qux" to "567")))
    }

    fun withHeaderMap() {
        val response = Response(200, Headers("foo" to "123", "bar" to "345"), null)
        val expected = Response(200, Headers("foo" to "123", "bar" to "345", "baz" to "456", "qux" to "567"), null)
        assertEquals(expected, response.withHeaders(mapOf("baz" to "456", "qux" to "567")))
    }
}

@Test
class HeadersTest {

    fun withHeader() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val expected = Headers("foo" to "123", "bar" to "345", "baz" to "456")
        assertEquals(expected, headers.withHeader("baz", "456"))
    }

    fun withHeaderOverride() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val expected = Headers("foo" to "123", "bar" to "456")
        assertEquals(expected, headers.withHeader("bar", "456"))
    }

    fun withHeaders() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val expected = Headers("foo" to "123", "bar" to "345", "baz" to "456", "qux" to "567")
        assertEquals(expected, headers.withHeaders("baz" to "456", "qux" to "567"))
    }

    fun withHeadersOverride() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val expected = Headers("foo" to "123", "bar" to "456", "baz" to "567")
        assertEquals(expected, headers.withHeaders("bar" to "456", "baz" to "567"))
    }

    fun plusHeader() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val expected = Headers("foo" to "123", "bar" to "345", "baz" to "456")
        assertEquals(expected, headers + ("baz" to "456"))
    }

    fun plusHeaderOverride() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val expected = Headers("foo" to "123", "bar" to "456")
        assertEquals(expected, headers + ("bar" to "456"))
    }

    fun plusHeaders() {
        val headers1 = Headers("foo" to "123", "bar" to "345")
        val headers2 = Headers("baz" to "456", "qux" to "567")
        val expected = Headers("foo" to "123", "bar" to "345", "baz" to "456", "qux" to "567")
        assertEquals(expected, headers1 + headers2)
    }

    fun plusHeadersOverride() {
        val headers1 = Headers("foo" to "123", "bar" to "345")
        val headers2 = Headers("bar" to "456", "baz" to "567")
        val expected = Headers("foo" to "123", "bar" to "456", "baz" to "567")
        assertEquals(expected, headers1 + headers2)
    }

    fun plusMap() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val map = mapOf("baz" to "456", "qux" to "567")
        val expected = Headers("foo" to "123", "bar" to "345", "baz" to "456", "qux" to "567")
        assertEquals(expected, headers + map)
    }

    fun plusMapOverride() {
        val headers = Headers("foo" to "123", "bar" to "345")
        val map = mapOf("bar" to "456", "baz" to "567")
        val expected = Headers("foo" to "123", "bar" to "456", "baz" to "567")
        assertEquals(expected, headers + map)
    }
}
