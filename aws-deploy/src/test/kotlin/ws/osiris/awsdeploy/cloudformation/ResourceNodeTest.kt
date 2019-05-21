package ws.osiris.awsdeploy.cloudformation

import org.testng.annotations.Test
import ws.osiris.aws.ApplicationConfig
import ws.osiris.aws.Stage
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.api
import kotlin.test.assertEquals

@Test
class ResourceTemplateTest {

    private val config = ApplicationConfig(
        applicationName = "notUsed",
        stages = listOf(
            Stage(
                name = "test",
                deployOnUpdate = false
            )
        )
    )

    private val api = api<ComponentsProvider> {
        get("/") {} // 1 resource (it's root so there's no resource for the REST resource, only for the method)
        post("/") {} // 1 (as above)
        get("/foo") {} // 2
        get("/foo/bar") {} // 2
        get("/baz") {} // 2
        get("/baz/bar") {} // 2
        post("/baz/bar") {} // 1 (same resource as above, only adds a method)
        get("/qux") {} // 2
        get("/blah") {} // 2
        get("/blah/foo") {} // 2
    }

    private val templates = Templates.create(
        api,
        config,
        setOf("UserParam1", "UserParam2"),
        "com.example.GeneratedLambda::handle",
        "testHash",
        "staticHash",
        "testApi.code",
        "testApi.jar",
        "dev",
        "bucketPrefix"
    )

    fun resourceCount() {
        val resourceTemplate = templates.apiTemplate.rootResource
        assertEquals(17, resourceTemplate.resourceCount)
        assertEquals(2, resourceTemplate.ownResourceCount)
        assertEquals(4, resourceTemplate.children.size)
        assertEquals(4, resourceTemplate.children[0].resourceCount) // /foo
        assertEquals(2, resourceTemplate.children[0].ownResourceCount)
        assertEquals(1, resourceTemplate.children[0].children.size)
        assertEquals(2, resourceTemplate.children[0].children[0].resourceCount) // /foo/bar
        assertEquals(2, resourceTemplate.children[0].children[0].ownResourceCount)
        assertEquals(0, resourceTemplate.children[0].children[0].children.size)
        assertEquals(5, resourceTemplate.children[1].resourceCount) // /baz
        assertEquals(2, resourceTemplate.children[1].ownResourceCount)
        assertEquals(1, resourceTemplate.children[1].children.size)
        assertEquals(3, resourceTemplate.children[1].children[0].resourceCount) // /baz/bar
        assertEquals(3, resourceTemplate.children[1].children[0].ownResourceCount)
        assertEquals(0, resourceTemplate.children[1].children[0].children.size)
    }

    fun partitionChildren() {
        val resourceTemplate = templates.apiTemplate.rootResource
        val partitions = resourceTemplate.partitionChildren(8, 6)
        assertEquals(3, partitions.size)
        assertEquals(1, partitions[0].size)
        assertEquals("foo", partitions[0][0].pathPart)
        assertEquals(4, partitions[0][0].resourceCount)
        assertEquals(1, partitions[1].size)
        assertEquals("baz", partitions[1][0].pathPart)
        assertEquals(5, partitions[1][0].resourceCount)
        assertEquals(2, partitions[2].size)
        assertEquals("qux", partitions[2][0].pathPart)
        assertEquals(2, partitions[2][0].resourceCount)
        assertEquals("blah", partitions[2][1].pathPart)
        assertEquals(4, partitions[2][1].resourceCount)
    }

    fun partitionChildrenLarge() {
        // This is an anonymised version of a real API
        val api = api<ComponentsProvider> {
            path("/a") {
                post("/a/{a}") {}
                post("/b/{a}") {}
            }
            get("/b/{a}") {}
            path("/c/a") {}
            path("/d/a") {
                post("/b") {}
            }
            post("/e") {}
            path("/f") {
                post("/a") {}
            }
            path("/g") {
                post("/a") {}
                post("/b/{a}") {}
            }
            path("/h") {
                get("/{a}/{b}") {}
            }
            path("/i") {
                get("/{a}/{b}") {}
                post("/{a}") {}
            }
            get("/j/{a}") {}
            path("/k") {
                post("/a") {}
                post("/b/{a}") {}
                post("/c/{a}") {}
                get("/d/{a}") {}
                post("/e/{a}") {}
                post("/f/{a}/{b}") {}
            }
            path("/l") {
                post("/a") {}
                post("/b/{a}") {}
                path("/c") {
                    get("/a/{a}/{b}") {}
                    post("/b/{a}/{b}") {}
                }
                get("/d/{a}") {}
                path("/e") {
                    post("/a") {}
                    post("/b/{a}") {}
                    get("/c/{a}") {}
                }
            }
            path("/m") {
                post("/a") {}
                post("/b/{a}") {}
                get("/c/{a}/a") {}
                post("/d/a/{a}") {}
                post("/d/b/{a}") {}
                get("/e/{a}/{b}") {}
            }
            path("/n") {
                path("/a") {
                    get("/a") {}
                }
                path("/b") {
                    get("/a/{a}") {}
                    get("/b/{a}") {}
                }
            }
            path("/o") {
                get("/a/{a}") {}
            }
            path("/p") {
                post("/a") {}
                post("/b/{a}") {}
                get("/c/a/a") {}
                get("/c/b/a") {}
                get("/c/c/a") {}
            }
            path("/q") {
                get("/a/a") {}
            }
            path("/r") {
                get("/a/a") {}
                post("/b/{a}") {}
            }
            path("/s") {
                post("/a/a/{a}") {}
                get("/b/{a}") {}
            }
            get("/t/{a}/a") {}
            get("/u/{a}/{b}/{c}") {}
            path("/v") {
                post("/{a}") {}
            }
            path("/w") {
                post("/a") {}
                post("/b/{a}/{b}") {}
                get("/c/{a}/{b}") {}
            }
            post("/x") {}
            path("/y") {
                post("/a") {}
                post("/b/{a}") {}
                post("/c/{a}") {}
                get("/d/{a}") {}
                post("/e/{a}") {}
                post("/f/{a}/{b}") {}
            }
        }
        val templates = Templates.create(
            api,
            config,
            setOf("UserParam1", "UserParam2"),
            "com.example.GeneratedLambda::handle",
            "testHash",
            "staticHash",
            "testApi.code",
            "testApi.jar",
            "dev",
            "bucketPrefix"
        )
        val rootResourceTemplate = templates.apiTemplate.rootResource
        val partitions = rootResourceTemplate.partitionChildren(150, 200)
        assertEquals(2, partitions.size)
        assertEquals(146, partitions[0].map { it.resourceCount }.sum())
        assertEquals(52, partitions[1].map { it.resourceCount }.sum())
    }

}
