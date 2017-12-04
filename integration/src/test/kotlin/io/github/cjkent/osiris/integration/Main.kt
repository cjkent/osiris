package io.github.cjkent.osiris.integration

import io.github.cjkent.osiris.server.Protocol
import io.github.cjkent.osiris.server.HttpTestClient


fun main(args: Array<String>) {
    if (args.size < 3) throw IllegalArgumentException("Required arguments: API ID, region, stage")
    val apiId = args[0]
    val region = args[1]
    val stage = args[2]
    val client = HttpTestClient(Protocol.HTTPS, "$apiId.execute-api.$region.amazonaws.com", 443, "/$stage")
    assertApi(client)
    println("Everything passed")
}
