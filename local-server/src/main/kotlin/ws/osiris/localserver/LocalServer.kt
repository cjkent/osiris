package ws.osiris.localserver

import com.beust.jcommander.Parameter
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.server.handler.HandlerList
import org.eclipse.jetty.server.handler.ResourceHandler
import org.eclipse.jetty.servlet.ServletContextHandler
import org.slf4j.LoggerFactory
import ws.osiris.core.Api
import ws.osiris.core.ComponentsProvider
import ws.osiris.core.ContentType
import ws.osiris.core.Headers
import ws.osiris.core.HttpHeaders
import ws.osiris.core.HttpMethod
import ws.osiris.core.Params
import ws.osiris.core.Request
import ws.osiris.core.RequestContextFactory
import ws.osiris.core.RouteNode
import ws.osiris.core.StaticRoute
import ws.osiris.core.match
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.stream.Collectors.joining
import jakarta.servlet.ServletConfig
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

private val log = LoggerFactory.getLogger("ws.osiris.localserver")

class OsirisServlet<T : ComponentsProvider> : HttpServlet() {

    private lateinit var routeTree: RouteNode<T>
    private lateinit var components: T
    private lateinit var requestContextFactory: RequestContextFactory
    private lateinit var binaryMimeTypes: Set<String>

    @Suppress("UNCHECKED_CAST")
    override fun init(config: ServletConfig) {
        val providedApi = config.servletContext.getAttribute(API_ATTRIBUTE) as Api<T>? ?:
            throw IllegalStateException("The Api instance must be a servlet context attribute keyed with $API_ATTRIBUTE")
        val providedComponents = config.servletContext.getAttribute(COMPONENTS_ATTRIBUTE)
        val contextFactory = config.servletContext.getAttribute(REQUEST_CONTEXT_FACTORY_ATTRIBUTE)
        routeTree = RouteNode.create(providedApi)
        components = providedComponents as T
        requestContextFactory = contextFactory as RequestContextFactory
        binaryMimeTypes = providedApi.binaryMimeTypes
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val method = HttpMethod.valueOf(req.method)
        val path = req.pathInfo
        val queryParams = Params.fromQueryString(req.queryString)
        val match = routeTree.match(method, path)
        if (match == null) {
            resp.write(404, Headers(), null)
            return
        }
        val headerMap = req.headerNames.iterator().asSequence().associateWith { req.getHeader(it) }
        val headers = Params(headerMap)
        val pathParams = Params(match.vars)
        val body = requestBody(headerMap, req)
        val context = requestContextFactory.createContext(method, path, headers, queryParams, pathParams, body)
        val request = Request(method, path, headers, queryParams, pathParams, context, body)
        val response = match.handler.invoke(components, request)
        resp.write(response.status, response.headers, response.body)
    }

    private fun requestBody(headers: Map<String, String>, req: HttpServletRequest): Any? {
        val contentType = headers[HttpHeaders.CONTENT_TYPE] ?: return req.bodyAsString()
        val (mimeType, _) = ContentType.parse(contentType)
        return if (binaryMimeTypes.contains(mimeType)) {
            req.inputStream.use { it.readBytes() }
        } else {
            req.bodyAsString()
        }
    }

    private fun ServletConfig.initParam(name: String): String =
        getInitParameter(name) ?: throw IllegalArgumentException("Missing init param $name")

    companion object {

        /** The attribute name used for storing the [Api] instance in the `ServletContext`. */
        const val API_ATTRIBUTE = "api"

        /** The attribute name used for storing the [ComponentsProvider] instance in the `ServletContext`. */
        const val COMPONENTS_ATTRIBUTE = "components"

        /** The attribute name used for storing the [RequestContextFactory] instance in the `ServletContext`. */
        const val REQUEST_CONTEXT_FACTORY_ATTRIBUTE = "requestContextFactory"
    }
}

private fun HttpServletRequest.bodyAsString(): String =
    BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8")).lines().collect(joining("\n"))

private fun HttpServletResponse.write(httpStatus: Int, headers: Headers, body: Any?) {
    status = httpStatus
    headers.headerMap.forEach { (name, value) -> addHeader(name, value) }
    when (body) {
        is String -> outputStream.writer().use { it.write(body) }
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
 * The `contextRoot` argument controls the URL on which the API is available. By default, the API is
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
    contextRoot: String = "",
    staticFilesDir: String? = null,
    requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
) {

    val server = createLocalServer(api, components, port, contextRoot, staticFilesDir, requestContextFactory)
    server.start()
    log.info("Server started at http://localhost:{}{}/", port, contextRoot)
    server.join()
}

internal fun <T : ComponentsProvider> createLocalServer(
    api: Api<T>,
    components: T,
    port: Int = 8080,
    contextRoot: String = "",
    staticFilesDir: String? = null,
    requestContextFactory: RequestContextFactory = RequestContextFactory.empty()
): Server {

    val server = Server(port)
    val servletContextHandler = ServletContextHandler()
    servletContextHandler.contextPath = "/"
    servletContextHandler.addServlet(OsirisServlet::class.java, "$contextRoot/*")
    servletContextHandler.setAttribute(OsirisServlet.API_ATTRIBUTE, api)
    servletContextHandler.setAttribute(OsirisServlet.COMPONENTS_ATTRIBUTE, components)
    servletContextHandler.setAttribute(OsirisServlet.REQUEST_CONTEXT_FACTORY_ATTRIBUTE, requestContextFactory)
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
    return if (api.staticFiles) {
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
        log.info("Serving static files from {}", Paths.get(staticFilesDir).normalize().toAbsolutePath())
        HandlerList(contextHandler, servletHandler)
    } else {
        log.debug("API does not contain static files, skipping static files configuration")
        servletHandler
    }
}

/**
 * The arguments needed to run a local server; populated by JCommander from the command-line.
 */
class ServerArgs {

    @Parameter(names = ["-p", "--port"])
    var port = 8080

    @Parameter(names = ["-r", "--root"])
    var root = ""
}
