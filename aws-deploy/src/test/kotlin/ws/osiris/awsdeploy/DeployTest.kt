package ws.osiris.awsdeploy

import org.intellij.lang.annotations.Language
import org.testng.annotations.Test
import kotlin.test.assertEquals

@Test
class DeployTest {

    fun generatedTemplateParameters() {
        val templateUrl = "https://\${bucketPrefix}test-app-code.s3.amazonaws.com/test-app.template"
        @Language("yaml")
        val template = """
            Resources:
              Foo:
                Type: AWS::Whatever
                Properties:
                  Bar: baz
              Bar:
                Type: AWS::CloudFormation::Stack
                Properties:
                  TemplateURL: "https://some_bucket.s3.amazonaws.com/whatever.template"
                  Parameters:
                    FooParam: fooValue
                    BarParam: barValue
              ApiStack:
                Type: AWS::CloudFormation::Stack
                Properties:
                  TemplateURL: "$templateUrl"
                  Parameters:
                    Param1: value1
                    Param2: value2
        """.trimIndent()
        val parameters = generatedTemplateParameters(template, "test-app-code", "test-app")
        assertEquals(setOf("Param1", "Param2"), parameters)
    }

}
