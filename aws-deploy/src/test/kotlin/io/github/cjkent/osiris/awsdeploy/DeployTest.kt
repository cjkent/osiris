package io.github.cjkent.osiris.awsdeploy

import org.intellij.lang.annotations.Language
import org.testng.annotations.Test
import kotlin.test.assertEquals

@Test
class DeployTest {

    fun generatedTemplateParameters() {
        val templateUrl = "https://s3-\${AWS::Region}.amazonaws.com/test-app.code/test-app.template"
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
                  TemplateURL: "https://s3-eu-west-1.amazonaws.com/some_bucket/whatever.template"
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
        val parameters = generatedTemplateParameters(template, "test-app.code", "test-app")
        assertEquals(setOf("Param1", "Param2"), parameters)
    }

}
