package io.github.cjkent.osiris.localserver

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import io.github.cjkent.osiris.core.API_COMPONENTS_CLASS
import io.github.cjkent.osiris.core.API_DEFINITION_CLASS
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.DataNotFoundException
import io.github.cjkent.osiris.core.EncodedBody
import io.github.cjkent.osiris.core.Headers
import io.github.cjkent.osiris.core.HttpMethod
import io.github.cjkent.osiris.core.Params
import io.github.cjkent.osiris.core.Request
import io.github.cjkent.osiris.core.RequestContext
import io.github.cjkent.osiris.core.RequestContextIdentity
import io.github.cjkent.osiris.core.RouteNode
import io.github.cjkent.osiris.core.StaticRoute
import io.github.cjkent.osiris.core.match
import io.github.cjkent.osiris.server.ApiFactory
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.server.handler.HandlerList
import org.eclipse.jetty.server.handler.ResourceHandler
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

class OsirisServlet<T : ComponentsProvider> : HttpServlet() {

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

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val method = HttpMethod.valueOf(req.method)
        val path = req.pathInfo
        val queryParams = Params.fromQueryString(req.queryString)
        val match = routeTree.match(method, path) ?: throw DataNotFoundException()
        val headerMap = req.headerNames.iterator().asSequence().associate { it to req.getHeader(it) }
        val headers = Params(headerMap)
        val pathParams = Params(match.vars)
        val request = Request(method, path, headers, queryParams, pathParams, emptyRequestContext, req.bodyAsString())
        val response = match.handler.invoke(components, request)
        resp.write(response.status, response.headers, response.body)
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
         * The attribute name used for storing the `ComponentsProvider` instance from the `ServletContext`.
         *
         * This is used for testing. Real deployments specify the name of the components class
         * which is instantiated using reflection.
         */
        const val COMPONENTS_ATTRIBUTE = "components"
    }
}

private fun HttpServletRequest.bodyAsString(): String =
    BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8")).lines().collect(joining("\n"))

private fun HttpServletResponse.write(httpStatus: Int, headers: Headers, body: Any?) {
    status = httpStatus
    headers.headerMap.forEach { name, value -> addHeader(name, value) }
    when (body) {
        is String -> outputStream.writer().use { it.write(body) }
        is EncodedBody -> if (body.body != null) outputStream.writer().use { it.write(body.body) }
        is ByteArray -> outputStream.use { it.write(body) }
        null -> return
        else -> throw IllegalArgumentException("Unexpected body type ${body.javaClass.name}, need String or ByteArray")
    }
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
fun <T : ComponentsProvider> runLocalServer(
    api: Api<T>,
    components: T,
    port: Int = 8080,
    contextRoot: String = ""
) {

    val server = createLocalServer(api, components, port, contextRoot)
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
    contextRoot: String = "",
    staticFilesDir: String? = null
): Server {

    val server = Server(port)
    val apiFactory = ApiFactory.create<ComponentsProvider>(
        OsirisServlet::class.java.classLoader,
        apiComponentsClass.jvmName,
        apiDefinitionClass.jvmName
    )
    val servletHandler = ServletHandler()
    val servletHolder = servletHandler.addServletWithMapping(OsirisServlet::class.java, contextRoot + "/*")
    servletHolder.setInitParameter(API_COMPONENTS_CLASS, apiComponentsClass.jvmName)
    servletHolder.setInitParameter(API_DEFINITION_CLASS, apiDefinitionClass.jvmName)
    val servletContextHandler = ServletContextHandler()
    servletContextHandler.contextPath = "/"
    servletContextHandler.servletHandler = servletHandler
    server.handler = configureStaticFiles(apiFactory.api, servletContextHandler, contextRoot, staticFilesDir)
    return server
}

internal fun <T : ComponentsProvider> createLocalServer(
    api: Api<T>,
    components: T,
    port: Int = 8080,
    contextRoot: String = "",
    staticFilesDir: String? = null
): Server {

    val server = Server(port)
    val servletContextHandler = ServletContextHandler()
    servletContextHandler.contextPath = "/"
    servletContextHandler.addServlet(OsirisServlet::class.java, contextRoot + "/*")
    servletContextHandler.setAttribute(OsirisServlet.API_ATTRIBUTE, api)
    servletContextHandler.setAttribute(OsirisServlet.COMPONENTS_ATTRIBUTE, components)
    server.handler = configureStaticFiles(api, servletContextHandler, contextRoot, staticFilesDir)
    return server
}

//--------------------------------------------------------------------------------------------------

private fun configureStaticFiles(
    api: Api<*>,
    servletHandler: ServletContextHandler,
    contextRoot: String,
    staticFilesDir: String?
): Handler {
    val staticRoutes = api.routes.filterIsInstance(StaticRoute::class.java)
    // TODO lift this restriction
    if (staticRoutes.size > 1) {
        throw IllegalArgumentException("Only one static file path is supported")
    }
    return if (!staticRoutes.isEmpty()) {
        if (staticFilesDir == null) {
            throw IllegalArgumentException("No static file location specified")
        }
        // TODO Don't use ResourceHandler
        // https://github.com/perwendel/spark/issues/316
        val resourceHandler = ResourceHandler()
        val staticRoute = staticRoutes[0]
        staticRoute.indexFile?.let { resourceHandler.welcomeFiles = arrayOf(it) }
        resourceHandler.resourceBase = staticFilesDir
        val contextHandler = ContextHandler(contextRoot + staticRoute.path)
        contextHandler.handler = resourceHandler
        HandlerList(contextHandler, servletHandler)
    } else {
        servletHandler
    }
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

// TODO it might be necessary to let the user specify this in case they are depending on values when testing
/** An empty request context. */
private val emptyRequestContext =
    RequestContext("", "", "", "", "", RequestContextIdentity("", "", "", "", "", "", "", "", "", "", "", ""))
