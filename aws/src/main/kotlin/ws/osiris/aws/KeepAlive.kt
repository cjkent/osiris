package ws.osiris.aws

import com.amazonaws.services.lambda.AWSLambda
import com.amazonaws.services.lambda.AWSLambdaClientBuilder
import com.amazonaws.services.lambda.model.InvocationType
import com.amazonaws.services.lambda.model.InvokeRequest
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.google.gson.Gson
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * The number of retries if the keep-alive call fails with access denied.
 *
 * Retrying is necessary because policy updates aren't visible immediately after the stack is updated.
 */
private const val RETRIES = 7

/**
 * Lambda that invokes other lambdas with keep-alive messages.
 *
 * It is triggered by a CloudWatch event containing the ARN of the lambda to keep alive and
 * the number of instances that should be kept alive.
 */
class KeepAliveLambda {

    private val lambdaClient: AWSLambda = AWSLambdaClientBuilder.defaultClient()
    private val gson: Gson = Gson()

    fun handle(trigger: KeepAliveTrigger) {
        log.debug("Triggering keep-alive, count: {}, function: {}", trigger.instanceCount, trigger.functionArn)
        // The lambda expects a request from API Gateway of this type - this fakes it
        val requestEvent = APIGatewayProxyRequestEvent().apply {
            resource = KEEP_ALIVE_RESOURCE
            headers = mapOf(KEEP_ALIVE_SLEEP to trigger.sleepTimeMs.toString())
        }
        val json = gson.toJson(requestEvent)
        val payloadBuffer = ByteBuffer.wrap(json.toByteArray())
        val invokeRequest = InvokeRequest().apply {
            functionName = trigger.functionArn
            invocationType = InvocationType.Event.name
            payload = payloadBuffer
        }

        /**
         * Invokes multiple copies of the function, retrying if access is denied.
         *
         * The retry is necessary because policy updates aren't visible immediately after the stack is updated.
         */
        tailrec fun invokeFunctions(attemptCount: Int = 1) {
            try {
                repeat(trigger.instanceCount) {
                    lambdaClient.invoke(invokeRequest)
                }
                log.debug("Keep-alive complete")
                return
            } catch (e: Exception) {
                if (attemptCount == RETRIES) throw e
                log.debug("Exception triggering keep-alive: {} {}", e.javaClass.name, e.message)
                Thread.sleep(2000L * attemptCount)
            }
            invokeFunctions(attemptCount + 1)
        }
        invokeFunctions()
    }

    companion object {
        private val log = LoggerFactory.getLogger(KeepAliveLambda::class.java)
    }
}

/**
 * Message sent from the CloudWatch event to trigger keep-alive calls.
 */
class KeepAliveTrigger(var functionArn: String = "", var instanceCount: Int = 0, var sleepTimeMs: Int = 200)
