package ${package}.localserver

import com.beust.jcommander.JCommander
import ws.osiris.localserver.ServerArgs
import ws.osiris.localserver.runLocalServer

import ${package}.core.api
import ${package}.core.config
import ${package}.core.createComponents

fun main(args: Array<String>) {
    val serverArgs = ServerArgs()
    JCommander.newBuilder().addObject(serverArgs).build().parse(*args)
    val components = createComponents()
    runLocalServer(api, components, config, serverArgs.port, serverArgs.root, "core/src/main/static")
}
