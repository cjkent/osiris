package ws.osiris.core

import com.google.common.collect.ImmutableMultimap
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

@Test
class ParamsTest {

    // ===== Single-Value Map Constructor Tests =====

    fun constructFromMap() {
        val params = Params(mapOf("foo" to "123", "bar" to "456"))
        assertEquals("123", params["foo"])
        assertEquals("456", params["bar"])
    }

    fun constructFromEmptyMap() {
        val params = Params(mapOf())
        assertEquals(null, params.optional("foo"))
    }

    fun getThrowsWhenMissing() {
        val params = Params(mapOf("foo" to "123"))
        assertFailsWith(IllegalArgumentException::class) { params["bar"] }
    }

    fun optionalReturnsNull() {
        val params = Params(mapOf("foo" to "123"))
        assertEquals(null, params.optional("bar"))
    }

    fun optionalReturnsValue() {
        val params = Params(mapOf("foo" to "123"))
        assertEquals("123", params.optional("foo"))
    }

    // ===== Case-Insensitive Lookup Tests =====

    fun caseInsensitiveLookup() {
        val params = Params(mapOf("Content-Type" to "application/json"))
        assertEquals("application/json", params["content-type"])
        assertEquals("application/json", params["CONTENT-TYPE"])
        assertEquals("application/json", params["Content-Type"])
    }

    fun caseInsensitiveOptional() {
        val params = Params(mapOf("Authorization" to "Bearer token"))
        assertEquals("Bearer token", params.optional("authorization"))
        assertEquals("Bearer token", params.optional("AUTHORIZATION"))
    }

    fun caseInsensitiveMissing() {
        val params = Params(mapOf("foo" to "bar"))
        assertEquals(null, params.optional("FOO-BAR"))
        assertFailsWith(IllegalArgumentException::class) { params["FOO-BAR"] }
    }

    // ===== Immutability Tests =====

    fun plusAddsParam() {
        val params1 = Params(mapOf("foo" to "123"))
        val params2 = params1 + ("bar" to "456")
        assertEquals("123", params1["foo"])
        assertEquals(null, params1.optional("bar"))
        assertEquals("123", params2["foo"])
        assertEquals("456", params2["bar"])
        // Check multimap
        assertEquals(1, params2.multiValues["foo"].size)
        assertEquals(1, params2.multiValues["bar"].size)
    }

    fun plusOverridesParam() {
        val params1 = Params(mapOf("foo" to "123"))
        val params2 = params1 + ("foo" to "456")
        // Single-value access returns the new value
        assertEquals("123", params1["foo"])
        assertEquals("456", params2["foo"])
        // Multimap contains both values
        assertEquals(1, params1.multiValues["foo"].size)
        assertEquals(2, params2.multiValues["foo"].size)
        assert(params2.multiValues["foo"].contains("123"))
        assert(params2.multiValues["foo"].contains("456"))
    }

    fun plusMultipleTimesToSameKey() {
        val params1 = Params(mapOf("foo" to "first"))
        val params2 = params1 + ("foo" to "second")
        val params3 = params2 + ("foo" to "third")
        
        // Single-value access returns last value
        assertEquals("third", params3["foo"])
        
        // Multimap contains all three values
        val values = params3.multiValues["foo"]
        assertEquals(3, values.size)
        assert(values.contains("first"))
        assert(values.contains("second"))
        assert(values.contains("third"))
    }

    fun plusWithExistingMultiValues() {
        val params1 = Params.fromListMap(mapOf("foo" to listOf("value1", "value2")))
        val params2 = params1 + ("foo" to "value3")
        
        // Single-value access returns the new value
        assertEquals("value3", params2["foo"])
        
        // Multimap contains all three values
        val values = params2.multiValues["foo"]
        assertEquals(3, values.size)
        assert(values.contains("value1"))
        assert(values.contains("value2"))
        assert(values.contains("value3"))
    }

    fun minusRemovesParam() {
        val params1 = Params(mapOf("foo" to "123", "bar" to "456"))
        val params2 = params1 - "bar"
        assertEquals("123", params2["foo"])
        assertEquals(null, params2.optional("bar"))
        assertEquals("456", params1["bar"]) // Original unchanged
        // Check multimap
        assertEquals(0, params2.multiValues["bar"].size)
        assertEquals(1, params2.multiValues["foo"].size)
    }

    fun minusRemovesAllMultiValues() {
        val params1 = Params.fromListMap(mapOf(
            "foo" to listOf("value1", "value2", "value3"),
            "bar" to listOf("other")
        ))
        val params2 = params1 - "foo"
        
        // foo is completely removed
        assertEquals(null, params2.optional("foo"))
        assertEquals(0, params2.multiValues["foo"].size)
        
        // bar is still there
        assertEquals("other", params2["bar"])
        assertEquals(1, params2.multiValues["bar"].size)
    }

    fun minusNonExistent() {
        val params1 = Params(mapOf("foo" to "123"))
        val params2 = params1 - "bar"
        assertEquals("123", params2["foo"])
        assertEquals(1, params2.multiValues["foo"].size)
    }

    fun minusCaseInsensitive() {
        val params1 = Params(mapOf("Content-Type" to "application/json"))
        val params2 = params1 - "Content-Type"
        
        // Should be removed (case-sensitive removal by key)
        assertEquals(null, params2.optional("content-type"))
        assertEquals(0, params2.multiValues["content-type"].size)
    }

    // ===== Query String Parsing Tests =====

    fun fromQueryStringSimple() {
        val params = Params.fromQueryString("foo=123&bar=456")
        assertEquals("123", params["foo"])
        assertEquals("456", params["bar"])
    }

    fun fromQueryStringEmpty() {
        val params = Params.fromQueryString("")
        assertEquals(null, params.optional("foo"))
    }

    fun fromQueryStringNull() {
        val params = Params.fromQueryString(null)
        assertEquals(null, params.optional("foo"))
    }

    fun fromQueryStringWhitespace() {
        val params = Params.fromQueryString("  ")
        assertEquals(null, params.optional("foo"))
    }

    fun fromQueryStringSingleParam() {
        val params = Params.fromQueryString("foo=123")
        assertEquals("123", params["foo"])
    }

    fun fromQueryStringNoValue() {
        val params = Params.fromQueryString("foo=")
        assertEquals("", params["foo"])
    }

    fun fromQueryStringNoEquals() {
        val params = Params.fromQueryString("foo")
        assertEquals("", params["foo"])
    }

    fun fromQueryStringUrlEncoded() {
        val params = Params.fromQueryString("name=John%20Doe&city=New%20York")
        assertEquals("John Doe", params["name"])
        assertEquals("New York", params["city"])
    }

    fun fromQueryStringSpecialChars() {
        val params = Params.fromQueryString("email=user%40example.com&path=%2Fapi%2Fv1")
        assertEquals("user@example.com", params["email"])
        assertEquals("/api/v1", params["path"])
    }

    fun fromQueryStringMultipleValues() {
        val params = Params.fromQueryString("foo=123&foo=456&foo=789")
        // Single value access returns last value (API Gateway behavior)
        assertEquals("789", params["foo"])
        // Multi-values should contain all three
        val values = params.multiValues["foo"]
        assertEquals(3, values.size)
        assert(values.contains("123"))
        assert(values.contains("456"))
        assert(values.contains("789"))
    }

    fun fromQueryStringMultipleValuesWithOthers() {
        val params = Params.fromQueryString("foo=123&bar=abc&foo=456&baz=xyz&foo=789")
        // Check single value access
        assertEquals("789", params["foo"])
        assertEquals("abc", params["bar"])
        assertEquals("xyz", params["baz"])
        // Check multi-values
        val fooValues = params.multiValues["foo"]
        assertEquals(3, fooValues.size)
        assertEquals(1, params.multiValues["bar"].size)
        assertEquals(1, params.multiValues["baz"].size)
    }

    fun fromQueryStringDuplicateKeysUrlEncoded() {
        val params = Params.fromQueryString("name=John%20Doe&name=Jane%20Smith&age=30")
        assertEquals("Jane Smith", params["name"]) // Last value
        assertEquals("30", params["age"])
        val nameValues = params.multiValues["name"]
        assertEquals(2, nameValues.size)
        assert(nameValues.contains("John Doe"))
        assert(nameValues.contains("Jane Smith"))
    }

    fun fromQueryStringEmptyValuesMultiple() {
        val params = Params.fromQueryString("foo=&foo=123&foo=")
        assertEquals("", params["foo"]) // Last value is empty
        val values = params.multiValues["foo"]
        assertEquals(3, values.size)
        // Count how many empty strings
        assertEquals(2, values.count { it == "" })
        assert(values.contains("123"))
    }

    fun fromQueryStringMixedSingleAndMulti() {
        val params = Params.fromQueryString("single=one&multi=first&multi=second&another=value")
        // Single value params
        assertEquals("one", params["single"])
        assertEquals("value", params["another"])
        // Multi value param
        assertEquals("second", params["multi"]) // Last value
        assertEquals(1, params.multiValues["single"].size)
        assertEquals(2, params.multiValues["multi"].size)
        assertEquals(1, params.multiValues["another"].size)
    }

    // ===== Multi-Value Tests =====

    fun constructFromMultimap() {
        val multimap = ImmutableMultimap.builder<String, String>()
            .put("foo", "123")
            .put("foo", "456")
            .put("bar", "789")
            .build()
        val params = Params(multimap)
        
        // Single value access returns last value (matches API Gateway behavior)
        assertEquals("456", params["foo"])
        assertEquals("789", params["bar"])
    }

    fun multiValuesAccessSingle() {
        val multimap = ImmutableMultimap.builder<String, String>()
            .put("foo", "123")
            .put("bar", "456")
            .build()
        val params = Params(multimap)
        
        val fooValues = params.multiValues["foo"]
        assertEquals(1, fooValues.size)
        assertEquals("123", fooValues.first())
    }

    fun multiValuesAccessMultiple() {
        val multimap = ImmutableMultimap.builder<String, String>()
            .put("foo", "123")
            .put("foo", "456")
            .put("foo", "789")
            .build()
        val params = Params(multimap)
        
        val values = params.multiValues["foo"]
        assertEquals(3, values.size)
        assert(values.contains("123"))
        assert(values.contains("456"))
        assert(values.contains("789"))
    }

    fun multiValuesCaseInsensitive() {
        val multimap = ImmutableMultimap.builder<String, String>()
            .put("Content-Type", "text/html")
            .put("Content-Type", "application/json")
            .build()
        val params = Params(multimap)
        
        val values1 = params.multiValues["content-type"]
        val values2 = params.multiValues["CONTENT-TYPE"]
        val values3 = params.multiValues["Content-Type"]
        
        assertEquals(2, values1.size)
        assertEquals(2, values2.size)
        assertEquals(2, values3.size)
    }

    fun multiValuesEmpty() {
        val params = Params(mapOf())
        val values = params.multiValues["nonexistent"]
        assertEquals(0, values.size)
    }

    // ===== Edge Cases =====

    fun emptyParamName() {
        val params = Params(mapOf("" to "value"))
        assertEquals("value", params[""])
    }

    fun emptyParamValue() {
        val params = Params(mapOf("foo" to ""))
        assertEquals("", params["foo"])
    }

    fun unicodeInParams() {
        val params = Params(mapOf("name" to "José", "city" to "São Paulo"))
        assertEquals("José", params["name"])
        assertEquals("São Paulo", params["city"])
    }

    fun whitespaceInKeys() {
        val params = Params(mapOf("foo bar" to "123"))
        assertEquals("123", params["foo bar"])
        assertEquals("123", params["FOO BAR"]) // Case-insensitive
    }

    fun duplicateKeysInMultimap() {
        val multimap = ImmutableMultimap.builder<String, String>()
            .put("foo", "first")
            .put("foo", "second")
            .put("foo", "third")
            .build()
        val params = Params(multimap)
        
        // Map should contain last value (matches API Gateway behavior)
        assertEquals("third", params.params["foo"])
        
        // But multiValues should have all three
        assertEquals(3, params.multiValues["foo"].size)
    }

    fun constructFromMapCreatesMultimap() {
        val params = Params(mapOf("foo" to "123", "bar" to "456"))
        
        // Should be accessible via multiValues
        assertEquals(1, params.multiValues["foo"].size)
        assertEquals("123", params.multiValues["foo"].first())
        assertEquals(1, params.multiValues["bar"].size)
        assertEquals("456", params.multiValues["bar"].first())
    }

    // ===== Map<String, List<String>> Factory Method Tests =====

    fun fromListMapSingleValues() {
        val params = Params.fromListMap(mapOf("foo" to listOf("123"), "bar" to listOf("456")))
        assertEquals("123", params["foo"])
        assertEquals("456", params["bar"])
        assertEquals(1, params.multiValues["foo"].size)
        assertEquals(1, params.multiValues["bar"].size)
    }

    fun fromListMapMultipleValues() {
        val params = Params.fromListMap(mapOf("foo" to listOf("123", "456", "789"), "bar" to listOf("abc")))
        // Last value wins for single-value access
        assertEquals("789", params["foo"])
        assertEquals("abc", params["bar"])
        // But all values are accessible via multiValues
        val fooValues = params.multiValues["foo"]
        assertEquals(3, fooValues.size)
        assert(fooValues.contains("123"))
        assert(fooValues.contains("456"))
        assert(fooValues.contains("789"))
        assertEquals(1, params.multiValues["bar"].size)
    }

    fun fromListMapEmptyList() {
        val params = Params.fromListMap(mapOf("foo" to listOf(), "bar" to listOf("123")))
        // Empty list means no value
        assertEquals(null, params.optional("foo"))
        assertEquals("123", params["bar"])
        assertEquals(0, params.multiValues["foo"].size)
    }

    fun fromListMapMixedSingleAndMulti() {
        val params = Params.fromListMap(mapOf(
            "single" to listOf("one"),
            "multiple" to listOf("first", "second", "third"),
            "another" to listOf("value")
        ))
        assertEquals("one", params["single"])
        assertEquals("third", params["multiple"]) // Last value
        assertEquals("value", params["another"])
        
        assertEquals(1, params.multiValues["single"].size)
        assertEquals(3, params.multiValues["multiple"].size)
        assertEquals(1, params.multiValues["another"].size)
    }

    fun fromListMapCaseInsensitive() {
        val params = Params.fromListMap(mapOf("Content-Type" to listOf("text/html", "application/json")))
        // Last value wins
        assertEquals("application/json", params["content-type"])
        assertEquals("application/json", params["CONTENT-TYPE"])
        
        val values = params.multiValues["content-type"]
        assertEquals(2, values.size)
        assert(values.contains("text/html"))
        assert(values.contains("application/json"))
    }

    // ===== Default Constructor Test =====

    fun defaultConstructor() {
        val params = Params()
        assertEquals(null, params.optional("foo"))
        assertEquals(0, params.multiValues["foo"].size)
    }
}
