package io.github.cjkent.osiris.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.testng.annotations.Test
import kotlin.test.assertEquals

/**
 * Tests for the functionality provided by the standard set of filters that are included by default.
 */
@Test
class StandardFilterTest {

    fun defaultContentType() {
        val api = api<ComponentsProvider> {
            get("/foo") { _ ->
                "Foo"
            }
        }
        val client = InMemoryTestClient.create(api)
        val (_, headers, _) = client.get("/foo")
        assertEquals(JSON_CONTENT_TYPE.header, headers[HttpHeaders.CONTENT_TYPE])
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
        val api = api<ComponentsProvider> {
            get("/nullbody") { req ->
                req.responseBuilder().build(null)
            }
            get("/stringbody") { _ ->
                """{"foo":"abc"}"""
            }
            get("/mapbody") { _ ->
                mapOf("foo" to 42, "bar" to "Bar")
            }
            get("/objectbody") { _ ->
                BodyObject(42, "Bar")
            }
        }
        val client = InMemoryTestClient.create(api)
        assertEquals(null, client.get("/nullbody").body)
        assertEquals("""{"foo":"abc"}""", client.get("/stringbody").body)
        assertEquals("""{"foo":42,"bar":"Bar"}""", client.get("/mapbody").body)
        assertEquals("""{"foo":42,"bar":"Bar"}""", client.get("/objectbody").body)
    }

    // TODO serialisation when the content type isn't JSON. not a high priority for now

    @Test
    fun exceptionMapping() {
        val api = api<ComponentsProvider> {
            get("/badrequest") { _ ->
                throw IllegalArgumentException("illegal arg")
            }
            get("/notfound") { _ ->
                throw DataNotFoundException("not found")
            }
            get("/badjson") { _ ->
                // This throws a JsonParseException which is mapped to a bad request
                jacksonObjectMapper().readValue("this is invalid JSON", Map::class.java)
            }
            get("/forbidden") { _ ->
                throw ForbiddenException("top secret")
            }
            get("/servererror") { _ ->
                throw RuntimeException("oh no!")
            }
        }
        val client = InMemoryTestClient.create(api)
        val (status1, _, body1) = client.get("/badrequest")
        assertEquals(400, status1)
        assertEquals("illegal arg", body1)

        val (status2, _, body2) = client.get("/notfound")
        assertEquals(404, status2)
        assertEquals("not found", body2)

        val (status3, _, body3) = client.get("/forbidden")
        assertEquals(403, status3)
        assertEquals("top secret", body3)

        val (status4, _, body4) = client.get("/servererror")
        assertEquals(500, status4)
        assertEquals("Server Error", body4)
    }

    fun testNoFilters() {
        val api = api<ComponentsProvider> {

            globalFilters = listOf()

            get("/foo") { _ ->
                "Foo"
            }
        }
        val client = InMemoryTestClient.create(api)
        val (_, headers, _) = client.get("/foo")
        assertEquals(null, headers[HttpHeaders.CONTENT_TYPE])
    }

    //--------------------------------------------------------------------------------------------------

    private class BodyObject(val foo: Int, val bar: String)
}
