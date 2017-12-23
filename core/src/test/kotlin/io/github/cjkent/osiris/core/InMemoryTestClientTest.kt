package io.github.cjkent.osiris.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.testng.Assert
import org.testng.annotations.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val components: ComponentsProvider = object : ComponentsProvider {}

private val staticDir = Paths.get("src/test/static")

@Test
class InMemoryTestClientTest {

    fun get() {
        val api = api<ComponentsProvider> {
            get("/foo") {
                "hello, world!"
            }
        }
        val client = InMemoryTestClient.create(components, api)
        val response = client.get("/foo")
        val body = response.body
        assertEquals(response.status, 200)
        assertTrue(body == "hello, world!")
    }

    fun staticFiles() {
        val api = api<ComponentsProvider> {
            staticFiles {
                path = "/public"
            }
        }
        val client = InMemoryTestClient.create(components, api, staticDir)

        val response1 = client.get("/public/index.html")
        val body1 = response1.body
        assertEquals(response1.status, 200)
        assertTrue(body1 is String && body1.contains("hello, world!"))

        val response2 = client.get("/public/foo/bar.html")
        val body2 = response2.body
        assertEquals(response2.status, 200)
        assertTrue(body2 is String && body2.contains("hello, bar!"))
    }

    fun staticFilesIndexFile() {
        val api = api<ComponentsProvider> {
            staticFiles {
                path = "/public"
                indexFile = "index.html"
            }
        }
        val client = InMemoryTestClient.create(components, api, staticDir)

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
        val client = InMemoryTestClient.create(components, api, staticDir)

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
        Assert.assertTrue(body5 is String && body5.contains("get hello"))
    }

    fun staticFilesNestedInPath() {
        val api = api<ComponentsProvider> {
            path("/foo") {
                staticFiles {
                    path = "/public"
                }
            }
        }
        val client = InMemoryTestClient.create(components, api, staticDir)

        val response1 = client.get("/foo/public/index.html")
        val body1 = response1.body
        assertEquals(response1.status, 200)
        assertTrue(body1 is String && body1.contains("hello, world!"))

        val response2 = client.get("/foo/public/foo/bar.html")
        val body2 = response2.body
        assertEquals(response2.status, 200)
        assertTrue(body2 is String && body2.contains("hello, bar!"))
    }

    fun requestContextFactory() {
        val api = api<ComponentsProvider> {
            get("/hello") { req ->
                mapOf("context" to req.context.params)
            }
        }
        val requestContextFactory = RequestContextFactory.fixed("stage" to "dev", "foo" to "bar")
        val client = InMemoryTestClient.create(components, api, requestContextFactory = requestContextFactory)
        val response = client.get("/hello")
        val body = response.body as String
        val bodyMap = jacksonObjectMapper().readValue<Map<String, Any>>(body)
        Assert.assertEquals(bodyMap["context"], mapOf("stage" to "dev", "foo" to "bar"))
    }
}
