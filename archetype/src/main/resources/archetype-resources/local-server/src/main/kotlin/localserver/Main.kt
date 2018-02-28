package ${package}.localserver

import com.beust.jcommander.JCommander
import io.github.cjkent.osiris.localserver.ServerArgs
import io.github.cjkent.osiris.localserver.runLocalServer

import ${package}.core.api
import ${package}.core.createComponents

fun main(args: Array<String>) {
    val serverArgs = ServerArgs()
    JCommander.newBuilder().addObject(serverArgs).build().parse(*args)
    val api = api
    val components = createComponents()
    runLocalServer(api, components, serverArgs.port, serverArgs.root, "core/src/main/static")
}
