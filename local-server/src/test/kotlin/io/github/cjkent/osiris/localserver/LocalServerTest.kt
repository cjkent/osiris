package io.github.cjkent.osiris.localserver

import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.api
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

private val components: ComponentsProvider = object : ComponentsProvider {}

private const val STATIC_DIR = "src/test/resources/static"

@Test
class LocalServerTest {

    fun get() {
        val api = api(ComponentsProvider::class) {
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
        val api = api(ComponentsProvider::class) {
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
        val api = api(ComponentsProvider::class) {
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
        val api = api(ComponentsProvider::class) {
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
}
