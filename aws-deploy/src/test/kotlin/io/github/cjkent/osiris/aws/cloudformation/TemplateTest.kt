package io.github.cjkent.osiris.aws.cloudformation

import io.github.cjkent.osiris.aws.ApplicationConfig
import io.github.cjkent.osiris.aws.Stage
import io.github.cjkent.osiris.awsdeploy.cloudformation.ApiTemplate
import io.github.cjkent.osiris.awsdeploy.cloudformation.ResourceTemplate
import io.github.cjkent.osiris.awsdeploy.cloudformation.writeTemplate
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.api
import org.intellij.lang.annotations.Language
import org.testng.annotations.Test
import java.io.StringWriter
import java.time.Duration
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
        val api = api<ComponentsProvider> {
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
        val config = ApplicationConfig(
            applicationName = "my-application",
            lambdaMemorySizeMb = 512,
            lambdaTimeout = Duration.ofSeconds(10),
            environmentVariables = mapOf(
                "FOO" to "foo value",
                "BAR" to "bar value"
            ),
            stages = listOf(
                Stage(
                    name = "dev",
                    deployOnUpdate = true,
                    variables = mapOf("STAGE_VAR" to "dev value")
                ),
                Stage(
                    name = "prod",
                    deployOnUpdate = false,
                    variables = mapOf("STAGE_VAR" to "prod value")
                )
            )
        )
        writeTemplate(
            writer,
            api,
            config,
            setOf("UserParam1", "UserParam2"),
            "com.example.GeneratedLambda::handle",
            "testHash",
            "staticHash",
            "testApi.code",
            "testApi.jar",
            true,
            "dev"
        )
        // TODO assertions
    }
}
