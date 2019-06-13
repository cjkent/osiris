package ws.osiris.aws.cloudformation

import org.intellij.lang.annotations.Language
import org.testng.annotations.Test
import ws.osiris.aws.ApplicationConfig
import ws.osiris.aws.Stage
import ws.osiris.awsdeploy.cloudformation.ApiTemplate
import ws.osiris.awsdeploy.cloudformation.ResourceTemplate
import ws.osiris.awsdeploy.cloudformation.Templates
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.api
import java.io.StringWriter
import java.time.Duration
import kotlin.test.assertEquals

@Test
class TemplateTest {

    fun apiTemplateOnly() {
        val apiTemplate = ApiTemplate(
            ResourceTemplate(listOf(), listOf(), "", "", true),
            "foo",
            "desc",
            null,
            setOf()
        )
        val writer = StringWriter()
        apiTemplate.write(writer)
        @Language("yaml")
        val expected = """
        |
        |  Api:
        |    Type: AWS::ApiGateway::RestApi
        |    Properties:
        |      Name: "foo"
        |      Description: "desc"
        |      FailOnWarnings: true
        |      BinaryMediaTypes: []
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
        Templates.create(
            api,
            config,
            setOf("UserParam1", "UserParam2"),
            "com.example.GeneratedLambda::handle",
            "testHash",
            "staticHash",
            "testApi.code",
            "testApi.jar",
            "dev"
        )
        // TODO assertions
    }
}
