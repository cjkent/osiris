package ws.osiris.aws

import com.amazonaws.services.lambda.AWSLambda
import com.amazonaws.services.lambda.AWSLambdaClientBuilder
import com.amazonaws.services.lambda.model.InvocationType
import com.amazonaws.services.lambda.model.InvokeRequest
import com.google.gson.Gson
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

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
        val jsonMap = mapOf("keepAlive" to mapOf("sleepTimeMs" to trigger.sleepTimeMs))
        val json = gson.toJson(jsonMap)
        val payloadBuffer = ByteBuffer.wrap(json.toByteArray())
        val invokeRequest = InvokeRequest().apply {
            functionName = trigger.functionArn
            invocationType = InvocationType.Event.name
            payload = payloadBuffer
        }
        repeat(trigger.instanceCount) {
            lambdaClient.invoke(invokeRequest)
        }
        log.debug("Keep-alive complete")
    }

    companion object {
        private val log = LoggerFactory.getLogger(KeepAliveLambda::class.java)
    }
}

/**
 * Message sent from the CloudWatch event to trigger keep-alive calls.
 */
class KeepAliveTrigger(var functionArn: String = "", var instanceCount: Int = 0, var sleepTimeMs: Int = 200)
