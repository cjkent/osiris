package io.github.cjkent.osiris.aws.cloudformation

import io.github.cjkent.osiris.awsdeploy.Stage
import io.github.cjkent.osiris.awsdeploy.cloudformation.ApiTemplate
import io.github.cjkent.osiris.awsdeploy.cloudformation.ResourceTemplate
import io.github.cjkent.osiris.awsdeploy.cloudformation.writeTemplate
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.api
import org.intellij.lang.annotations.Language
import org.testng.annotations.Test
import java.io.StringWriter
import kotlin.test.assertEquals

@Test
class TemplateTest {

    fun apiTemplateOnly() {
        val apiTemplate = ApiTemplate("foo", "desc", ResourceTemplate(listOf(), "", "", listOf(), true, ""))
        val writer = StringWriter()
        apiTemplate.write(writer)
        @Language("yaml")
        val expected = """
        |
        |  Api:
        |    Type: AWS::ApiGateway::RestApi
        |    Properties:
        |      Name: foo
        |      Description: "desc"
        |      FailOnWarnings: true
""".trimMargin()
        assertEquals(expected, writer.toString())
    }

    fun createFromApi() {
        val api = api(ComponentsProvider::class) {
            get("/") { }
            get("/foo") { }
            get("/foo/bar") { }
            get("/baz") { }
            staticFiles {
                path = "/public"
                indexFile = "index.html"
            }
        }
        val writer = StringWriter()
        val stages = listOf(
            Stage("dev", mapOf("foo" to "devFoo", "bar" to "devBar"), true, "the dev stage"),
            Stage("prod", mapOf("foo" to "prodFoo", "bar" to "prodBar"), true, "the prod stage")
        )
        writeTemplate(
            writer,
            api,
            "testApi",
            "com.example",
            "A test API",
            "com.example.GeneratedLambda::handle",
            512,
            5,
            "testHash",
            "testApi.code",
            "testApi.jar",
            true,
            null,
            stages,
            mapOf("ENV_VAR" to "envVarValue"))
        // TODO assertions
    }
}
