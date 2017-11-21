package io.github.cjkent.osiris.example.dynamodb.localserver

import io.github.cjkent.osiris.example.dynamodb.core.api
import io.github.cjkent.osiris.example.dynamodb.core.createComponents
import io.github.cjkent.osiris.localserver.runLocalServer

fun main(args: Array<String>) {
    val api = api
    val components = createComponents()
    runLocalServer(api, components, staticFilesDir = "core/src/main/resources/static")
}
