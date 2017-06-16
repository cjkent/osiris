package io.github.cjkent.osiris.api

import org.testng.annotations.Test
import kotlin.test.assertEquals

/**
 * Tests for the functionality provided by the standard set of filters that are included by default.
 */
@Test
class StandardFilterTest {

    fun defaultContentType() {
        val client = InMemoryTestClient.create {
            get("/foo") { _ ->
                "Foo"
            }
        }
        val (_, headers, _) = client.get("/foo")
        assertEquals(ContentTypes.APPLICATION_JSON, headers[HttpHeaders.CONTENT_TYPE])
    }

    /**
     * Tests the automatic encoding of the response body into a JSON string.
     *
     * Handling of body types by content type:
     *   * content type = JSON
     *     * null - no body
     *     * string - assumed to be JSON, used as-is
     *     * ByteArray - base64 encoded - does API Gateway return this as binary? or a base64 encoded string?
     *     * object - converted to a JSON string using Jackson
     *   * content type != JSON
     *     * null - no body
     *     * string - used as-is, no base64 - Jackson should handle escaping when AWS does the conversion
     *     * ByteArray - base64 encoded - does API Gateway return this as binary? or a base64 encoded string?
     *     * any other type throws an exception
     */
    fun serialiseObjectsToJson() {
        val client = InMemoryTestClient.create {
            get("/nullbody") { req ->
                req.responseBuilder().build(null)
            }
            get("/stringbody") { req ->
                """{"foo":"abc"}"""
            }
            get("/mapbody") { req ->
                mapOf("foo" to 42, "bar" to "Bar")
            }
            get("/objectbody") { req ->
                BodyObject(42, "Bar")
            }
        }
        assertEquals(null, client.get("/nullbody").body)
        assertEquals("""{"foo":"abc"}""", client.get("/stringbody").body)
        assertEquals("""{"foo":42,"bar":"Bar"}""", client.get("/mapbody").body)
        assertEquals("""{"foo":42,"bar":"Bar"}""", client.get("/objectbody").body)
    }

    fun exceptionMapping() {

    }

    //--------------------------------------------------------------------------------------------------

    private class BodyObject(val foo: Int, val bar: String)
}
