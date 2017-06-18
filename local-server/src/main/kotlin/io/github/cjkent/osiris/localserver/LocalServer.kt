package io.github.cjkent.osiris.localserver

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cjkent.osiris.api.API_COMPONENTS_CLASS
import io.github.cjkent.osiris.api.API_DEFINITION_CLASS
import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.ContentTypes
import io.github.cjkent.osiris.api.DataNotFoundException
import io.github.cjkent.osiris.api.HttpException
import io.github.cjkent.osiris.api.HttpHeaders
import io.github.cjkent.osiris.api.HttpMethod
import io.github.cjkent.osiris.api.Params
import io.github.cjkent.osiris.api.Request
import io.github.cjkent.osiris.api.RouteNode
import io.github.cjkent.osiris.api.match
import io.github.cjkent.osiris.server.ApiFactory
import io.github.cjkent.osiris.server.encodeResponseBody
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHandler
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.stream.Collectors.joining
import javax.servlet.ServletConfig
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.localserver")

class OsirisServlet<T : ApiComponents> : HttpServlet() {

    private val objectMapper = ObjectMapper()
    private lateinit var routeTree: RouteNode<T>
    private lateinit var components: T

    @Suppress("UNCHECKED_CAST")
    override fun init(config: ServletConfig) {
        val providedApi = config.servletContext.getAttribute(API_ATTRIBUTE) as Api<T>?
        val providedComponents = config.servletContext.getAttribute(COMPONENTS_ATTRIBUTE)
        if (providedApi != null && providedComponents != null) {
            routeTree = RouteNode.create(providedApi)
            components = providedComponents as T
        } else {
            val apiComponentsClassName = config.initParam(API_COMPONENTS_CLASS)
            val apiDefinitionClassName = config.initParam(API_DEFINITION_CLASS)
            val apiFactory = ApiFactory.create<T>(
                javaClass.classLoader,
                apiComponentsClassName,
                apiDefinitionClassName)
            routeTree = RouteNode.create(apiFactory.api)
            components = apiFactory.createComponents()
        }
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) = try {
        val method = HttpMethod.valueOf(req.method)
        val path = req.pathInfo
        val queryParams = Params.fromQueryString(req.queryString)
        val match = routeTree.match(method, path) ?: throw DataNotFoundException()
        val headerMap = req.headerNames.iterator().asSequence().associate { it to req.getHeader(it) }
        val headers = Params(headerMap)
        val pathParams = Params(match.vars)
        val request = Request(method, path, headers, queryParams, pathParams, req.bodyAsString(), false)
        val response = match.handler.invoke(components, request)
        val contentType = response.headers[HttpHeaders.CONTENT_TYPE] ?: ContentTypes.APPLICATION_JSON
        val (encodedBody, _) = encodeResponseBody(response.body, contentType, objectMapper)
        resp.write(response.status, response.headers, encodedBody)
    } catch (e: HttpException) {
        resp.error(e.httpStatus, e.message)
    } catch (e: JsonProcessingException) {
        resp.error(400, "Failed to parse JSON: ${e.message}")
    } catch (e: IllegalArgumentException) {
        resp.error(400, e.message)
    } catch (e: Exception) {
        resp.error(500, "Server Error")
    }

    private fun ServletConfig.initParam(name: String): String =
        getInitParameter(name) ?: throw IllegalArgumentException("Missing init param $name")

    companion object {

        /**
         * The attribute name used for storing the `Api` instance from the `ServletContext`.
         *
         * This is used for testing. Real deployments specify the name of the `ApiDefinition` class
         * which is instantiated using reflection.
         */
        const val API_ATTRIBUTE = "api"
        /**
         * The attribute name used for storing the `ApiComponents` instance from the `ServletContext`.
         *
         * This is used for testing. Real deployments specify the name of the components class
         * which is instantiated using reflection.
         */
        const val COMPONENTS_ATTRIBUTE = "components"
    }
}

private fun HttpServletRequest.bodyAsString(): String =
    BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8")).lines().collect(joining("\n"))

private fun HttpServletResponse.write(httpStatus: Int, headers: Map<String, String>, body: Any?) {
    status = httpStatus
    headers.forEach { name, value -> addHeader(name, value) }
    when (body) {
        is String -> outputStream.writer().use { it.write(body) }
        is ByteArray -> outputStream.use { it.write(body) }
    }
}

private fun HttpServletResponse.error(httpStatus: Int, message: String?) {
    val headers = mapOf(HttpHeaders.CONTENT_TYPE to ContentTypes.TEXT_PLAIN)
    write(httpStatus, headers, message)
}

/**
 * Runs a very basic Jetty server running on the specified port that serves [OsirisServlet] from the root.
 *
 * This is a convenience method for running a local server from a `main` method. The implementation
 * runs and joins the server, so the method never returns and the server can only be stopped by killing
 * the process.
 *
 * The `contextRoot` argument controls the URL on which the API is available. By default the API is
 * available at:
 *
 *     http://localhost:8080/
 *
 * If `contextRoot` is `/foo` then the API would be available at:
 *
 *     http://localhost:8080/foo/
 */
fun runLocalServer(
    apiComponentsClass: KClass<*>,
    apiDefinitionClass: KClass<*>,
    port: Int = 8080,
    contextRoot: String = ""
) {

    val server = createLocalServer(apiComponentsClass, apiDefinitionClass, port, contextRoot)
    server.start()
    log.info("Server started at http://localhost:{}{}/", port, contextRoot)
    server.join()
}

/**
 * Creates a very basic Jetty server running on the specified port that serves [OsirisServlet] from the root.
 *
 * The `contextRoot` argument controls the URL on which the API is available. By default the API is
 * available at:
 *
 *     http://localhost:8080/
 *
 * If `contextRoot` is `/foo` then the API would be available at:
 *
 *     http://localhost:8080/foo/
 */
fun createLocalServer(
    apiComponentsClass: KClass<*>,
    apiDefinitionClass: KClass<*>,
    port: Int = 8080,
    contextRoot: String = ""
): Server {

    val server = Server(port)
    val servletHandler = ServletHandler()
    val servletHolder = servletHandler.addServletWithMapping(OsirisServlet::class.java, contextRoot + "/*")
    servletHolder.setInitParameter(API_COMPONENTS_CLASS, apiComponentsClass.jvmName)
    servletHolder.setInitParameter(API_DEFINITION_CLASS, apiDefinitionClass.jvmName)
    server.handler = servletHandler
    return server
}

internal fun <T : ApiComponents> createLocalServer(
    api: Api<T>,
    components: T,
    port: Int = 8080,
    contextRoot: String = ""
): Server {

    val server = Server(port)
    val servletHandler = ServletContextHandler()
    servletHandler.addServlet(OsirisServlet::class.java, contextRoot + "/*")
    server.handler = servletHandler
    servletHandler.servletContext.setAttribute(OsirisServlet.API_ATTRIBUTE, api)
    servletHandler.servletContext.setAttribute(OsirisServlet.COMPONENTS_ATTRIBUTE, components)
    return server
}

/**
 * The arguments needed to run a local server; populated by JCommander from the command-line.
 */
class ServerArgs {

    @Parameter(names = arrayOf("-p", "--port"))
    var port = 8080

    @Parameter(names = arrayOf("-r", "--root"))
    var root = ""

    @Parameter(names = arrayOf("-c", "--components"), required = true)
    lateinit var componentsClass: String

    @Parameter(names = arrayOf("-d", "--definition"), required = true)
    lateinit var definitionClass: String
}

fun main(args: Array<String>) {
    val serverArgs = ServerArgs()
    JCommander.newBuilder().addObject(serverArgs).build().parse(*args)
    val componentsClass = Class.forName(serverArgs.componentsClass).kotlin
    val definitionClass = Class.forName(serverArgs.definitionClass).kotlin
    runLocalServer(componentsClass, definitionClass, serverArgs.port, serverArgs.root)
}
