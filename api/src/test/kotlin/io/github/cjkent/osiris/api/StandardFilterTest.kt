package io.github.cjkent.osiris.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.testng.annotations.Test
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
     *     * any other type throws an exception. or should it just use toString()? seems friendlier
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

    // TODO serialisation when the content type isn't JSON. not a high priority for now

    // TODO this is disabled because the exception mapping is done in the lambda and servlet ATM
    // once it is done in a filter this test will pass
    @Test(enabled = false)
    fun exceptionMapping() {
        val client = InMemoryTestClient.create {
            get("/badrequest") { req ->
                throw IllegalArgumentException("illegal arg")
            }
            get("/notfound") { req ->
                throw DataNotFoundException("not found")
            }
            get("/badjson") { req ->
                // This throws a JsonParseException which is mapped to a bad request
                jacksonObjectMapper().readValue("this is invalid JSON", Map::class.java)
            }
            get("/forbidden") { req ->
                throw ForbiddenException("top secret")
            }
            get("/servererror") { req ->
                throw RuntimeException("oh no!")
            }
        }
        val (status1, _, body1) = client.get("/badrequest")
        assertEquals(400, status1)
        assertEquals("illegal arg", body1)

        val (status2, _, body2) = client.get("/notfound")
        assertEquals(404, status2)
        assertEquals("not found", body2)

        val (status3, _, body3) = client.get("/badjson")
        assertEquals(400, status3)
        assertTrue(Pattern.matches("Failed to pars JSON.*", body3 as String))

        val (status4, _, body4) = client.get("/forbidden")
        assertEquals(403, status4)
        assertEquals("top secret", body4)

        val (status5, _, body5) = client.get("/servererror")
        assertEquals(500, status5)
        assertEquals("Server Error", body5)
    }

    //--------------------------------------------------------------------------------------------------

    private class BodyObject(val foo: Int, val bar: String)
}
