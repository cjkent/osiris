package ws.osiris.integration

import ws.osiris.server.HttpTestClient
import ws.osiris.server.Protocol

fun main(args: Array<String>) {
    if (args.size < 3) throw IllegalArgumentException("Required arguments: API ID, region, stage")
    val apiId = args[0]
    val region = args[1]
    val stage = args[2]
    val client = HttpTestClient(Protocol.HTTPS, "$apiId.execute-api.$region.amazonaws.com", 443, "/$stage")
    assertApi(client)
    println("Everything passed")
}
