package io.github.cjkent.osiris.localserver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.RequestContextFactory
import io.github.cjkent.osiris.core.api
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

private val components: ComponentsProvider = object : ComponentsProvider {}

private const val STATIC_DIR = "src/test/static"

@Test
class LocalServerTest {

    fun get() {
        val api = api<ComponentsProvider> {
            get("/foo") {
                "hello, world!"
            }
        }
        LocalHttpTestClient.create(components, api).use { client ->
            val response = client.get("/foo")
            val body = response.body
            assertEquals(response.status, 200)
            assertTrue(body == "hello, world!")
        }
    }

    fun staticFiles() {
        val api = api<ComponentsProvider> {
            staticFiles {
                path = "/public"
            }
        }
        LocalHttpTestClient.create(components, api, STATIC_DIR).use { client ->
            val response1 = client.get("/public/index.html")
            val body1 = response1.body
            assertEquals(response1.status, 200)
            assertTrue(body1 is String && body1.contains("hello, world!"))

            val response2 = client.get("/public/foo/bar.html")
            val body2 = response2.body
            assertEquals(response2.status, 200)
            assertTrue(body2 is String && body2.contains("hello, bar!"))
        }
    }

    fun staticFilesIndexFile() {
        val api = api<ComponentsProvider> {
            staticFiles {
                path = "/public"
                indexFile = "index.html"
            }
        }
        LocalHttpTestClient.create(components, api, STATIC_DIR).use { client ->
            val response1 = client.get("/public")
            val body1 = response1.body
            assertTrue(body1 is String && body1.contains("hello, world!"))

            val response2 = client.get("/public/")
            val body2 = response2.body
            assertTrue(body2 is String && body2.contains("hello, world!"))

            val response3 = client.get("/public/index.html")
            val body3 = response3.body
            assertTrue(body3 is String && body3.contains("hello, world!"))
        }
    }

    fun staticFilesNestedInPath() {
        val api = api<ComponentsProvider> {
            path("/foo") {
                staticFiles {
                    path = "/public"
                }
            }
        }
        LocalHttpTestClient.create(components, api, STATIC_DIR).use { client ->
            val response1 = client.get("/foo/public/index.html")
            val body1 = response1.body
            assertEquals(response1.status, 200)
            assertTrue(body1 is String && body1.contains("hello, world!"))

            val response2 = client.get("/foo/public/foo/bar.html")
            val body2 = response2.body
            assertEquals(response2.status, 200)
            assertTrue(body2 is String && body2.contains("hello, bar!"))
        }
    }

    fun staticFilesAtRoot() {
        val api = api<ComponentsProvider> {
            staticFiles {
                path = "/"
                indexFile = "index.html"
            }
            get("/hello") {
                "get hello"
            }
        }
        LocalHttpTestClient.create(components, api, STATIC_DIR).use { client ->
            val response1 = client.get("")
            val body1 = response1.body
            assertTrue(body1 is String && body1.contains("hello, world!"))

            val response2 = client.get("/")
            val body2 = response2.body
            assertTrue(body2 is String && body2.contains("hello, world!"))

            val response3 = client.get("/index.html")
            val body3 = response3.body
            assertTrue(body3 is String && body3.contains("hello, world!"))

            val response4 = client.get("/foo/bar.html")
            val body4 = response4.body
            assertTrue(body4 is String && body4.contains("hello, bar!"))

            val response5 = client.get("/hello")
            val body5 = response5.body
            assertTrue(body5 is String && body5.contains("get hello"))
        }
    }

    fun requestContextFactory() {
        val api = api<ComponentsProvider> {
            get("/hello") { req ->
                mapOf("context" to req.context.params)
            }
        }
        val requestContextFactory = RequestContextFactory.fixed("stage" to "dev", "foo" to "bar")
        LocalHttpTestClient.create(components, api, requestContextFactory = requestContextFactory).use { client ->
            val response = client.get("/hello")
            val body = response.body as String
            val bodyMap = jacksonObjectMapper().readValue<Map<String, Any>>(body)
            assertEquals(bodyMap["context"], mapOf("stage" to "dev", "foo" to "bar"))
        }
    }
}
