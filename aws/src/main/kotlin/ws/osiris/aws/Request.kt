package ws.osiris.aws

import ws.osiris.core.Request

/** The AWS stage variables; this is an extension property to avoid putting AWS concepts into the core module. */
@Suppress("UNCHECKED_CAST")
val Request.stageVariables: Map<String, String>
    get() = this.attributes["stageVariables"] as? Map<String, String>? ?: mapOf()

/** The AWS stage name; this is an extension property to avoid putting AWS concepts into the core module. */
val Request.stageName: String get() = this.context.optional("stage") ?: "UNKNOWN"
