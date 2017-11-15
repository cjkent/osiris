package ${package}.localserver

import io.github.cjkent.osiris.localserver.runLocalServer

import ${package}.core.api
import ${package}.core.createComponents

fun main(args: Array<String>) {
    val api = api
    val components = createComponents()
    runLocalServer(api, components, staticFilesDir = "../core/src/main/resources/static")
}
