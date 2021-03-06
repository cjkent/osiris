package ws.osiris.aws

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.intellij.lang.annotations.Language

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Test
class RequestTest {

    /**
     * Tests deserialization of the request JSON into a [ws.osiris.core.Request].
     */
    fun deserializeInput() {
        // plain Jackson ObjectMapper (without the Kotlin module). it's what AWS uses
        val objectMapper = ObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val requestEvent = objectMapper.readValue(requestJson, APIGatewayProxyRequestEvent::class.java)
        val request = buildRequest(requestEvent, TestContext())
        assertEquals("/foo", request.path)
        assertEquals("GET", request.method.name)
        assertEquals(mapOf("foo" to "bar", "baz" to "qux"), request.queryParams.params)
        assertTrue(request.body is ByteArray)
        assertEquals("the body text", String(request.requireBinaryBody()))
        val stageVars = mapOf("FOO" to "123", "BAR" to "ABC")
        assertEquals(stageVars, request.stageVariables)
        assertEquals("dev", request.stageName)
    }

    /**
     * A sample of the JSON passed to the lambda by API Gateway.
     */
    @Language("json")
    private val requestJson = """
    {
        "resource": "/foo",
        "path": "/base/foo",
        "httpMethod": "GET",
        "headers": {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
            "Accept-Encoding": "gzip, deflate, br",
            "Accept-Language": "en-GB,en-US;q=0.9,en;q=0.8",
            "CloudFront-Forwarded-Proto": "https",
            "CloudFront-Is-Desktop-Viewer": "true",
            "CloudFront-Is-Mobile-Viewer": "false",
            "CloudFront-Is-SmartTV-Viewer": "false",
            "CloudFront-Is-Tablet-Viewer": "false",
            "CloudFront-Viewer-Country": "GB",
            "dnt": "1",
            "Host": "f2nzkw5aga.execute-api.eu-west-1.amazonaws.com",
            "Referer": "https://eu-west-1.console.aws.amazon.com/apigateway/home?region=eu-west-1",
            "upgrade-insecure-requests": "1",
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.94 Safari/537.36 OPR/49.0.2725.64",
            "Via": "2.0 87ce1a2818e8b605bc0c86bdab0851bf.cloudfront.net (CloudFront)",
            "X-Amz-Cf-Id": "T_1lBQv4Ru-At46EDRWeU0Rsx5u-VBfrkp4ELwSOT4XEyjqSVlcmhQ==",
            "X-Amzn-Trace-Id": "Root=1-5a5d283f-0e48e9917e6bff831f424b82",
            "X-Forwarded-For": "146.199.137.70, 52.46.38.16",
            "X-Forwarded-Port": "443",
            "X-Forwarded-Proto": "https"
        },
        "queryStringParameters": {
            "foo": "bar",
            "baz": "qux"
        },
        "pathParameters": null,
        "stageVariables": {
          "FOO": "123",
          "BAR": "ABC"
        },
        "requestContext": {
            "requestTime": "15/Jan/2018:22:16:31 +0000",
            "path": "/dev",
            "accountId": "12345678",
            "protocol": "HTTP/1.1",
            "resourceId": "a8nhuga3f9",
            "stage": "dev",
            "requestTimeEpoch": 1516054591536,
            "requestId": "bd63ecc1-fa41-11e7-b81a-e950967c4905",
            "identity": {
                "cognitoIdentityPoolId": null,
                "accountId": null,
                "cognitoIdentityId": null,
                "caller": null,
                "sourceIp": "146.199.137.70",
                "accessKey": null,
                "cognitoAuthenticationType": null,
                "cognitoAuthenticationProvider": null,
                "userArn": null,
                "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.94 Safari/537.36 OPR/49.0.2725.64",
                "user": null
            },
            "resourcePath": "/",
            "httpMethod": "GET",
            "apiId": "f2nzkw5aga"
        },
        "body": "dGhlIGJvZHkgdGV4dA==",
        "isBase64Encoded": true
    }
    """.trimIndent()
}

private class TestContext : Context {

    override fun getAwsRequestId(): String {
        throw UnsupportedOperationException("getAwsRequestId not implemented")
    }

    override fun getLogStreamName(): String {
        throw UnsupportedOperationException("getLogStreamName not implemented")
    }

    override fun getClientContext(): ClientContext {
        throw UnsupportedOperationException("getClientContext not implemented")
    }

    override fun getFunctionName(): String {
        throw UnsupportedOperationException("getFunctionName not implemented")
    }

    override fun getRemainingTimeInMillis(): Int {
        throw UnsupportedOperationException("getRemainingTimeInMillis not implemented")
    }

    override fun getLogger(): LambdaLogger {
        throw UnsupportedOperationException("getLogger not implemented")
    }

    override fun getInvokedFunctionArn(): String {
        throw UnsupportedOperationException("getInvokedFunctionArn not implemented")
    }

    override fun getMemoryLimitInMB(): Int {
        throw UnsupportedOperationException("getMemoryLimitInMB not implemented")
    }

    override fun getLogGroupName(): String {
        throw UnsupportedOperationException("getLogGroupName not implemented")
    }

    override fun getFunctionVersion(): String {
        throw UnsupportedOperationException("getFunctionVersion not implemented")
    }

    override fun getIdentity(): CognitoIdentity {
        throw UnsupportedOperationException("getIdentity not implemented")
    }
}
