package ws.osiris.aws

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import ws.osiris.core.Request

/** The AWS stage variables; this is an extension property to avoid putting AWS concepts into the core module. */
@Suppress("UNCHECKED_CAST")
val Request.stageVariables: Map<String, String>
    get() = this.attributes[STAGE_VARS_ATTR] as? Map<String, String>? ?: mapOf()

/** The event passed to the lambda by AWS. */
val Request.lambdaEvent: APIGatewayProxyRequestEvent?
    // If this isn't available in the attributes it could be created from the context
    get() = this.attributes[LAMBDA_EVENT_ATTR] as? APIGatewayProxyRequestEvent

/** The context passed to the lambda by AWS. */
val Request.lambdaContext: Context?
    get() = this.attributes[LAMBDA_CONTEXT_ATTR] as? Context

/** The AWS stage name; this is an extension property to avoid putting AWS concepts into the core module. */
val Request.stageName: String get() = this.context.optional("stage") ?: "UNKNOWN"
