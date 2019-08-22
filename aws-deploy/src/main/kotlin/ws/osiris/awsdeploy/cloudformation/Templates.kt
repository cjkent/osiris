// java.lang.Long.toHexString() is more useful than the Kotlin equivalent as it is always positive
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package ws.osiris.awsdeploy.cloudformation

import com.google.common.hash.Hashing
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import ws.osiris.aws.ApplicationConfig
import ws.osiris.aws.AuthConfig
import ws.osiris.aws.CognitoUserPoolsAuth
import ws.osiris.aws.CustomAuth
import ws.osiris.aws.Stage
import ws.osiris.aws.VpcConfig
import ws.osiris.awsdeploy.staticFilesBucketName
import ws.osiris.core.Api
import ws.osiris.core.Auth
import ws.osiris.core.FixedRouteNode
import ws.osiris.core.HttpMethod
import ws.osiris.core.NoAuth
import ws.osiris.core.RouteNode
import ws.osiris.core.StaticRouteNode
import ws.osiris.core.VariableRouteNode
import java.io.Writer
import java.lang.Long
import java.time.Duration
import java.util.UUID

private val log = LoggerFactory.getLogger("ws.osiris.awsdeploy.cloudformation")

/** The maximum number of CloudFormation resources allowed in a single YAML file. */
private const val MAX_FILE_RESOURCES: Int = Int.MAX_VALUE

/**
 * A template representing some CloudFormation YAML.
 *
 * A template typically contains the YAML for a single logical section of the CloudFormation template.
 *
 * It might contain one or more CloudFormation resources, or part of the file that contains data but no
 * resources (e.g. outputs, parameters).
 */
interface Template {

    /** The number of CloudFormation resources in the template. */
    val resourceCount: Int

    fun write(writer: Writer)
}

/**
 * A template representing some CloudFormation YAML for a REST resource or method.
 *
 * These resources different from [Template] implementations because they can be in the main CloudFormation
 * template file or in a nested stack in a different file. This means the references to the API and parent
 * resource aren't known until after construction and must be passed into the [write] function.
 */
interface RestTemplate {

    /** The number of CloudFormation resources in the template. */
    val resourceCount: Int

    fun write(writer: Writer, parentRef: String, lambdaRef: String)
}

/**
 * Contains the templates for the individual CloudFormation resources that need to be written to template files.
 *
 * Handles writing the files and splitting the REST resource templates across multiple files if necessary.
 */
internal class Templates(
    internal val apiTemplate: ApiTemplate,
    private val parametersTemplate: ParametersTemplate,
    private val lambdaTemplate: LambdaTemplate,
    private val publishLambdaTemplate: PublishLambdaTemplate,
    private val authTemplate: Template?,
    private val staticFilesRoleTemplate: StaticFilesRoleTemplate?,
    private val deploymentTemplate: DeploymentTemplate,
    private val stageTemplates: List<StageTemplate>,
    private val keepAliveTemplate: KeepAliveTemplate?,
    private val staticFilesBucketTemplate: S3BucketTemplate?,
    private val outputsTemplate: OutputsTemplate,
    private val appName: String,
    private val codeBucket: String
) {

    /** The CloudFormation YAML files defining the application. */
    internal val files: List<CloudFormationFile> = createFiles()

    private fun createFiles(): List<CloudFormationFile> {
        val resourceCount = apiTemplate.resourceCount +
            parametersTemplate.resourceCount +
            lambdaTemplate.resourceCount +
            publishLambdaTemplate.resourceCount +
            (authTemplate?.resourceCount ?: 0) +
            (staticFilesRoleTemplate?.resourceCount ?: 0) +
            deploymentTemplate.resourceCount +
            stageTemplates.map { it.resourceCount }.sum() +
            (keepAliveTemplate?.resourceCount ?: 0) +
            (staticFilesBucketTemplate?.resourceCount ?: 0) +
            outputsTemplate.resourceCount
        // The 10 is a fudge factor - after dividing up the resources across files we need to add extra resources
        // to the main file to reference each of the other files as nested stacks.
        // The partition operation would need to be iterative with the number of partitions being fed back into
        // the calculation. This seems like a lot of hassle for the smallest of edge cases.
        // This fudge give space for 10 nested stacks which would allow an API with ~2000 resources.
        // If you have one of those then the resource limit is the least of your problems.
        val firstFileMaxRestResources = MAX_FILE_RESOURCES - resourceCount - 10
        log.debug("CloudFormation resources: {}", apiTemplate.rootResource.resourceCount + resourceCount)
        log.debug("CloudFormation non-REST resources: {}", resourceCount)
        log.debug("CloudFormation REST resources: {}", apiTemplate.rootResource.resourceCount)
        log.debug("CloudFormation REST resources in main file: {}", firstFileMaxRestResources)
        // Partitions will always have a length of at least 1
        // If the length is 1 then all CloudFormation resources will fit into a single CloudFormation file
        // If the length is greater than 1 then resources from the first partition go in the main file and the other
        // partitions each go into a nested stack defined in a different CloudFormation file
        val partitions = apiTemplate.rootResource.partitionChildren(firstFileMaxRestResources, MAX_FILE_RESOURCES)
        val partitionSizes = partitions.map { ptn -> ptn.map { it.resourceCount }.sum() }
        log.debug("CloudFormation REST resource partition sizes: {}", partitionSizes)
        val nestedPartitions = partitions.subList(1, partitions.size)
        val restStackCount = nestedPartitions.size
        val restStackNames = (1..restStackCount).map { "RestStack$it" }
        val restStackFiles = (1..restStackCount).map { "$appName.rest$it.template" }
        // Indicates whether the nested stack needs a parameter Authorizer for API Gateway authorizer
        val authorizerParam = authTemplate != null
        val restStackTemplates = (0 until restStackCount).map {
            RestStackTemplate(restStackNames[it], restStackFiles[it], codeBucket, authorizerParam)
        }
        // Replace the children with only the resources that are in the main file
        // The resources from the other partitions are in separate files
        val rootResourceTemplate = apiTemplate.rootResource.copy(children = partitions[0])
        val updatedApiTemplate = apiTemplate.copy(rootResource = rootResourceTemplate)
        val mainFile = MainCloudFormationFile(
            "$appName.template",
            parametersTemplate,
            outputsTemplate,
            updatedApiTemplate,
            deploymentTemplate,
            restStackTemplates,
            authTemplate,
            lambdaTemplate,
            publishLambdaTemplate,
            staticFilesRoleTemplate,
            *stageTemplates.toTypedArray(),
            keepAliveTemplate,
            staticFilesBucketTemplate
        )
        val restFiles = nestedPartitions.mapIndexed { idx, partition ->
            RestCloudFormationFile(restStackFiles[idx], partition, authorizerParam)
        }
        return listOf(mainFile) + restFiles
    }

    companion object {
        /**
         * Writes a CloudFormation template for all the resources needed for the API:
         *
         * * API Gateway resources, methods and integrations for endpoints handled by the lambda
         * * API Gateway resources, methods, integrations, method responses and integration responses for
         *   endpoints serving static files from S3
         * * The lambda function
         * * The role used by the lambda function (unless an existing role is provided)
         * * The permissions for the role (unless an existing role is provided)
         * * The stages
         * * A deployment (if any stages are defined)
         * * The S3 bucket from which static files are served (unless an existing bucket is provided)
         */
        fun create(
            api: Api<*>,
            appConfig: ApplicationConfig,
            templateParams: Set<String>,
            lambdaHandler: String,
            codeHash: String,
            staticHash: String?,
            codeBucket: String,
            codeKey: String,
            envName: String?,
            accountId: String
        ): Templates {

            val authTypes = api.routes.map { it.auth }.toSet()
            val cognitoAuth = if (authTypes.contains(CognitoUserPoolsAuth)) {
                log.debug("Found endpoints with Cognito User Pools auth")
                true
            } else {
                false
            }
            val customAuth = if (authTypes.contains(CustomAuth)) {
                log.debug("Found endpoints with custom auth")
                true
            } else {
                false
            }
            val authConfig = appConfig.authConfig
            // If the authConfig is provided it means the custom auth lambda or cognito user pool is defined outside this
            // stack and its ARN is provided. which means there is no need for a template parameter to pass in the ARN.
            // the ARN is known and can be directly included in the template.
            // however, if custom auth or cognito auth is not used, it means the custom auth lambda or cognito user pool
            // is defined in the stack and must be passed into the generated template
            val cognitoAuthParam = if (cognitoAuth && appConfig.authConfig == null) {
                log.debug("Found endpoints with Cognito auth but no external auth config. " +
                    "Will create template parameter CognitoUserPoolArn. " +
                    "User pool must be defined in root.template")
                true
            } else {
                false
            }
            val customAuthParam = if (customAuth && appConfig.authConfig == null) {
                log.debug("Found endpoints with custom auth but no external auth config. " +
                    "Will create template parameter CustomAuthArn. " +
                    "Custom auth lambda must be defined in root.template")
                true
            } else {
                false
            }
            val parametersTemplate = ParametersTemplate(cognitoAuthParam, customAuthParam, templateParams)
            val staticFilesBucket: String
            val bucketTemplate: S3BucketTemplate?
            if (api.staticFiles) {
                // This is needed because appConfig.staticFilesBucket can't be smart cast
                val configStaticBucket = appConfig.staticFilesBucket
                if (configStaticBucket == null) {
                    staticFilesBucket = staticFilesBucketName(appConfig.applicationName, envName, accountId)
                    bucketTemplate = S3BucketTemplate(staticFilesBucket)
                } else {
                    staticFilesBucket = configStaticBucket
                    bucketTemplate = null
                }
            } else {
                staticFilesBucket = "notUsed" // TODO this smells bad - make it nullable all the way down?
                bucketTemplate = null
            }
            val apiTemplate = ApiTemplate.create(
                api,
                appConfig.applicationName,
                appConfig.applicationDescription,
                envName,
                staticFilesBucket,
                staticHash
            )
            val lambdaTemplate = LambdaTemplate(
                appConfig.lambdaName,
                lambdaHandler,
                appConfig.lambdaMemorySizeMb,
                appConfig.lambdaTimeout.seconds.toInt(),
                codeBucket,
                codeKey,
                appConfig.environmentVariables,
                templateParams,
                envName,
                appConfig.vpcConfig,
                parametersTemplate.vpcSubnetIdsParamPresent,
                parametersTemplate.vpcSecurityGroupIdsParamPresent
            )
            val publishLambdaTemplate = PublishLambdaTemplate(codeHash)
            val authTemplate = if (customAuth) {
                CustomAuthorizerTemplate(authConfig)
            } else if (cognitoAuth) {
                CognitoAuthorizerTemplate(authConfig)
            } else {
                null
            }
            val staticFilesRoleTemplate = if (api.staticFiles) {
                StaticFilesRoleTemplate("arn:aws:s3:::$staticFilesBucket")
            } else {
                null
            }
            val deploymentTemplate = DeploymentTemplate()
            val stageTemplates = appConfig.stages.map { StageTemplate(it) }
            val keepAlive = appConfig.keepAliveCount > 0
            val keepAliveTemplate = if (keepAlive) {
                KeepAliveTemplate(
                    appConfig.keepAliveCount,
                    appConfig.keepAliveInterval,
                    appConfig.keepAliveSleep,
                    codeBucket,
                    codeKey
                )
            } else {
                null
            }
            val authorizer = cognitoAuth || customAuth
            val outputsTemplate = OutputsTemplate(codeBucket, codeKey, authorizer, keepAlive)
            return Templates(
                apiTemplate,
                parametersTemplate,
                lambdaTemplate,
                publishLambdaTemplate,
                authTemplate,
                staticFilesRoleTemplate,
                deploymentTemplate,
                stageTemplates,
                keepAliveTemplate,
                bucketTemplate,
                outputsTemplate,
                appConfig.applicationName,
                codeBucket
            )
        }
    }
}

//--------------------------------------------------------------------------------------------------

internal data class ApiTemplate(
    internal val rootResource: ResourceTemplate,
    private val name: String,
    private val description: String?,
    private val envName: String?,
    private val binaryMimeTypes: Set<String>
) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        val name = if (envName == null) this.name else "${this.name}.$envName"
        val binaryTypes = binaryMimeTypes.joinToString(",", "[", "]") { "\"$it\"" }
        @Language("yaml")
        val template = """
        |
        |  Api:
        |    Type: AWS::ApiGateway::RestApi
        |    Properties:
        |      Name: "$name"
        |      Description: "${description ?: name}"
        |      FailOnWarnings: true
        |      BinaryMediaTypes: $binaryTypes
""".trimMargin()
        writer.write(template)
        rootResource.write(writer, "!GetAtt Api.RootResourceId", "LambdaVersion.FunctionArn")
    }

    companion object {

        fun create(
            api: Api<*>,
            name: String,
            description: String?,
            envName: String?,
            staticFilesBucket: String?,
            staticHash: String?
        ): ApiTemplate {

            val rootNode = RouteNode.create(api)
            val rootTemplate = resourceTemplate(rootNode, staticFilesBucket, staticHash, true, "")
            return ApiTemplate(rootTemplate, name, description, envName, api.binaryMimeTypes)
        }

        // 3 things are needed for each resource template
        //   1) resource name
        //   2) ref to parent
        //   3) ref used by methods and children
        //
        // 3 cases:
        //   root node (no YAML is generated for this as it is always present)
        //     1) not used
        //     2) not used
        //     3) !Ref root resource
        //   child of root
        //     1) generated from ID
        //     2) !Ref root resource
        //     3) !Ref name
        //   all others
        //     1) generated from ID
        //     2) !Ref parent name
        //     3) !Ref name
        //
        // generate name from ID; same in all cases
        // pass in parentRef - use blank for initial case (not used), use resource name when recursing
        // use isRoot flag to decide whether to use root !Ref or resource name for method's ref to resource
        private fun resourceTemplate(
            node: RouteNode<*>,
            staticFilesBucket: String?,
            staticHash: String?,
            isRoot: Boolean,
            parentPath: String
        ): ResourceTemplate {

            val pathPart = when (node) {
                is VariableRouteNode<*> -> "{${node.name}}"
                else -> node.name
            }
            val path = "$parentPath/$pathPart"
            val resourceName = resourceName(path)
            // the reference used by methods and child resources to refer to the current resource
            val resourceRef = if (isRoot) "!GetAtt Api.RootResourceId" else "!Ref $resourceName"
            val methods = methodTemplates(node, resourceName, resourceRef, staticFilesBucket, staticHash)
            val fixedChildTemplates = fixedChildResourceTemplates(node, staticFilesBucket, staticHash, path)
            val staticProxyTemplates = staticProxyResourceTemplates(node, staticFilesBucket, staticHash, path)
            val variableChildTemplates = variableChildResourceTemplates(node, staticFilesBucket, staticHash, path)
            val childResourceTemplates = fixedChildTemplates + variableChildTemplates + staticProxyTemplates
            return ResourceTemplate(methods, childResourceTemplates, pathPart, resourceName, isRoot)
        }

        /**
         * Creates resource templates for the fixed children of the node.
         */
        private fun fixedChildResourceTemplates(
            node: RouteNode<*>,
            staticFilesBucket: String?,
            staticHash: String?,
            parentPath: String
        ): List<ResourceTemplate> = node.fixedChildren.values.map {
            resourceTemplate(it, staticFilesBucket, staticHash, false, parentPath)
        }

        /**
         * Creates resource templates for the fixed children of the node.
         */
        private fun variableChildResourceTemplates(
            node: RouteNode<*>,
            staticFilesBucket: String?,
            staticHash: String?,
            parentPath: String
        ): List<ResourceTemplate> {
            return when (val variableChild = node.variableChild) {
                null -> listOf()
                else -> listOf(resourceTemplate(variableChild, staticFilesBucket, staticHash, false, parentPath))
            }
        }

        /**
         * Creates a resource for the endpoint that serves arbitrary static files from an S3 bucket.
         *
         * Any file name specified after the endpoint root is used to find a file in the S3 bucket. For example,
         * if the static files are served from `/foo`, a request for `/foo/bar/baz.html` will return the file
         * `bar/baz.html` from the S3 bucket.
         *
         * The resource uses greedy path matching to match any request whose path is below the endpoint. This
         * uses the path part `{proxy+}`.
         *
         * If the node is not a [StaticRouteNode] an empty list is returned.
         */
        private fun staticProxyResourceTemplates(
            node: RouteNode<*>,
            staticFilesBucket: String?,
            staticHash: String?,
            parentPath: String
        ): List<ResourceTemplate> = if (node is StaticRouteNode<*>) {
            if (staticFilesBucket == null) throw IllegalStateException("Index file specified with no static files bucket")
            log.debug("Creating static root template with hash {}, bucket {}", staticHash, staticFilesBucket)
            val pathPart = "{proxy+}"
            val path = "$parentPath/$pathPart"
            val proxyChildName = resourceName(path)
            val proxyChildRef = "!Ref $proxyChildName"
            val proxyChildMethodTemplate = StaticRootMethodTemplate(
                proxyChildName,
                proxyChildRef,
                node.auth,
                staticFilesBucket,
                staticHash
            )
            val proxyChildren = listOf(proxyChildMethodTemplate)
            listOf(ResourceTemplate(proxyChildren, listOf(), pathPart, proxyChildName, false))
        } else {
            listOf()
        }

        /**
         * Creates the templates for all resource methods for a node.
         */
        private fun methodTemplates(
            node: RouteNode<*>,
            resourceName: String,
            resourceRef: String,
            staticFilesBucket: String?,
            staticHash: String?
        ): List<MethodTemplate> = when (node) {
            is FixedRouteNode<*> -> lambdaMethodTemplates(node, resourceName, resourceRef)
            is VariableRouteNode<*> -> lambdaMethodTemplates(node, resourceName, resourceRef)
            is StaticRouteNode<*> -> indexFileMethodTemplates(node, resourceName, resourceRef, staticFilesBucket, staticHash)
        }

        /**
         * Creates the templates for all resource methods for a node representing an endpoint handled by a lambda.
         */
        private fun lambdaMethodTemplates(
            node: RouteNode<*>,
            resourceName: String,
            resourceRef: String
        ): List<LambdaMethodTemplate> = node.handlers.map { (httpMethod, pair) ->
            LambdaMethodTemplate(resourceName, resourceRef, httpMethod, pair.second)
        }

        /**
         * Creates the template for serving the index file when a request is received for the static files
         * endpoint that doesn't specify a file name.
         *
         * If the node doesn't have a static file then an empty list is returned.
         */
        private fun indexFileMethodTemplates(
            node: StaticRouteNode<*>,
            resourceName: String,
            resourceRef: String,
            staticFilesBucket: String?,
            staticHash: String?
        ): List<MethodTemplate> {

            log.debug("Creating static index file template with hash {}, bucket {}", staticHash, staticFilesBucket)
            val indexFile = node.indexFile
            return if (indexFile == null) {
                listOf()
            } else {
                if (staticFilesBucket == null) {
                    throw IllegalStateException("Index file specified with no static files bucket")
                }
                listOf(
                    StaticIndexFileMethodTemplate(
                        resourceName,
                        resourceRef,
                        node.auth,
                        staticFilesBucket,
                        staticHash,
                        indexFile
                    )
                )
            }
        }

        /**
         * if the same resource ID is reused in multiple deployments referring to a different path then
         * CloudFormation gets confused and the deployment fails.
         * the resource needs to have a stable ID derived from the path. ideally this would be guaranteed
         * to be unique, but resource IDs must be alphanumeric so deriving an ID from a path would be fiddly.
         * for now it is sufficient and very easy to use a hash of the path. there is a possibility of
         * generating the same ID for endpoints with different paths but it is vanishingly small.
         */
        private fun resourceName(path: String): String {
            val hash = Hashing.farmHashFingerprint64().hashString(path, Charsets.UTF_8).asLong()
            val id = Long.toHexString(hash)
            return "Resource$id"
        }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * A template for a single resource (a path part) in a REST API.
 *
 * This doesn't implement [Template] because the reference to the parent isn't known when it is
 * created so it needs more arguments to the [write] function.
 *
 * This is because resources can be defined in a nested stack in which case those references are to parameters
 * rather than to other resources in the same file.
 */
internal data class ResourceTemplate(
    internal val methods: List<MethodTemplate>,
    internal val children: List<ResourceTemplate>,
    internal val pathPart: String,
    private val name: String,
    private val isRoot: Boolean
) : RestTemplate {

    override val resourceCount: Int

    /** The number of CloudFormation resources at this node in the path tree; doesn't include children. */
    val ownResourceCount: Int

    init {
        // If this template represents the root REST resource then it doesn't have a CloudFormation resource
        val templateResourceCount = if (isRoot) 0 else 1
        ownResourceCount = templateResourceCount + methods.size
        resourceCount = ownResourceCount + children.map { it.resourceCount }.sum()
    }

    /**
     * Writes YAML for the resource and its sub-resources to the writer.
     *
     * [parentRef] is the reference to the parent resource. If this resource is in the main CloudFormation file
     * with the API definition then it is `!Ref <parent ID>`. If this resource is in a nested stack then it is
     * a reference to a parameter whose name is the parent ID: `!Ref <parent ID>`. If the parent is the root
     * resource and the resource is in the main file then it is `!GetAtt Api.RootResourceId`.
     */
    override fun write(writer: Writer, parentRef: String, lambdaRef: String) {
        // don't need to create a CF resource for the root API gateway resource, it's always there
        if (!isRoot) {
            @Language("yaml")
            val template = """
            |
            |  $name:
            |    Type: AWS::ApiGateway::Resource
            |    Properties:
            |      RestApiId: !Ref Api
            |      ParentId: $parentRef
            |      PathPart: "$pathPart"
""".trimMargin()
            writer.write(template)
        }
        // This is slightly obscure. The root resource ref is passed into the root
        // resource as the parent ref. This isn't used as a parent ref because the
        // root resource doesn't have a parent or any YAML. The root resource passes
        // this down to its children to use as their parent ref.
        // It might be cleaner to have a RootResourceTemplate separate from
        // ResourceTemplate
        val thisRef = if (isRoot) parentRef else "!Ref $name"
        for (method in methods) {
            method.write(writer, thisRef, lambdaRef)
        }
        for (child in children) {
            child.write(writer, thisRef, lambdaRef)
        }
    }

    /**
     * Returns a sequence containing every [ResourceTemplate] in the tree rooted at this template; the
     * sequence includes this template and all its descendants.
     */
    internal fun treeSequence(): Sequence<ResourceTemplate> =
        sequenceOf(this) + children.asSequence().flatMap { it.treeSequence() }
}

//--------------------------------------------------------------------------------------------------

sealed class MethodTemplate : RestTemplate {
    internal abstract val name: String
}

//--------------------------------------------------------------------------------------------------

internal class LambdaMethodTemplate(
    resourceName: String,
    private val resourceRef: String,
    private val httpMethod: HttpMethod,
    private val auth: Auth?
) : MethodTemplate() {

    override val resourceCount = 1

    override val name = "$resourceName$httpMethod"

    override fun write(writer: Writer, parentRef: String, lambdaRef: String) {
        // this is easier to do in a regular string because the ${} can be escaped
        val uri = "arn:aws:apigateway:\${AWS::Region}:lambda:path/2015-03-31/functions/\${$lambdaRef}/invocations"
        @Language("yaml")
        val template = """
        |
        |  $name:
        |    Type: AWS::ApiGateway::Method
        |    Properties:
        |      HttpMethod: $httpMethod
        |      ResourceId: $resourceRef
        |      RestApiId: !Ref Api
        |      ${authSnippet(auth)}
        |      Integration:
        |        IntegrationHttpMethod: POST
        |        Type: AWS_PROXY
        |        Uri: !Sub $uri
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class StaticRootMethodTemplate(
    resourceName: String,
    private val resourceRef: String,
    private val auth: Auth?,
    private val staticFilesBucket: String,
    private val staticHash: String?
) : MethodTemplate() {

    override val resourceCount = 1

    override val name: String = "${resourceName}GET"

    override fun write(writer: Writer, parentRef: String, lambdaRef: String) {
        val arn = "arn:aws:apigateway:\${AWS::Region}:s3:path/$staticFilesBucket/$staticHash/{object}"
        @Language("yaml")
        val template = """
        |
        |  $name:
        |    Type: AWS::ApiGateway::Method
        |    Properties:
        |      HttpMethod: GET
        |      ResourceId: $resourceRef
        |      RestApiId: !Ref Api
        |      ${authSnippet(auth)}
        |      RequestParameters:
        |        method.request.path.proxy: true
        |      Integration:
        |        IntegrationHttpMethod: GET
        |        Type: AWS
        |        Uri: !Sub $arn
        |        Credentials: !GetAtt StaticFilesRole.Arn
        |        RequestParameters:
        |          integration.request.path.object: method.request.path.proxy
        |        IntegrationResponses:
        |          - StatusCode: 200
        |            ResponseParameters:
        |              method.response.header.Content-Type: integration.response.header.Content-Type
        |              method.response.header.Content-Length: integration.response.header.Content-Length
        |          - StatusCode: 403
        |            SelectionPattern: 403
        |          - StatusCode: 404
        |            SelectionPattern: 404
        |      MethodResponses:
        |        - StatusCode: 200
        |          ResponseParameters:
        |            method.response.header.Content-Type: true
        |            method.response.header.Content-Length: true
        |        - StatusCode: 403
        |        - StatusCode: 404
""".trimMargin()
        writer.write(template)
    }
}

internal class StaticIndexFileMethodTemplate(
    resourceName: String,
    private val resourceRef: String,
    private val auth: Auth?,
    private val staticFilesBucket: String,
    private val staticHash: String?,
    private val indexFile: String
) : MethodTemplate() {

    override val resourceCount = 1

    override val name: String = "${resourceName}GET"

    override fun write(writer: Writer, parentRef: String, lambdaRef: String) {
        val arn = "arn:aws:apigateway:\${AWS::Region}:s3:path/$staticFilesBucket/$staticHash/$indexFile"
        @Language("yaml")
        val template = """
        |
        |  $name:
        |    Type: AWS::ApiGateway::Method
        |    Properties:
        |      HttpMethod: GET
        |      ResourceId: $resourceRef
        |      RestApiId: !Ref Api
        |      ${authSnippet(auth)}
        |      Integration:
        |        IntegrationHttpMethod: GET
        |        Type: AWS
        |        Uri: !Sub $arn
        |        Credentials: !GetAtt StaticFilesRole.Arn
        |        IntegrationResponses:
        |          - StatusCode: 200
        |            ResponseParameters:
        |              method.response.header.Content-Type: integration.response.header.Content-Type
        |              method.response.header.Content-Length: integration.response.header.Content-Length
        |          - StatusCode: 403
        |            SelectionPattern: 403
        |          - StatusCode: 404
        |            SelectionPattern: 404
        |      MethodResponses:
        |        - StatusCode: 200
        |          ResponseParameters:
        |            method.response.header.Content-Type: true
        |            method.response.header.Content-Length: true
        |        - StatusCode: 403
        |        - StatusCode: 404
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class LambdaTemplate(
    private val functionName: String?,
    private val lambdaHandler: String,
    private val memorySize: Int,
    private val timeout: Int,
    private val codeS3Bucket: String,
    private val codeS3Key: String,
    private val envVars: Map<String, String>,
    private val templateParams: Set<String>,
    private val envName: String?,
    private val vpcConfig: VpcConfig?,
    private val vpcSubnetIdsParamPresent: Boolean,
    private val vpcSecurityGroupIdsParamPresent: Boolean
) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        // TODO escape the values
        val userVars = envVars.map { (k, v) -> "$k: \"$v\"" }
        val templateVars = templateParams.map { "$it: !Ref $it" }
        val envNameVars = envName?.let { listOf("ENVIRONMENT_NAME: \"$envName\"") } ?: listOf()
        val vars = userVars + templateVars + envNameVars
        val fnName = if (functionName != null) {
            "FunctionName: $functionName"
        } else {
            ""
        }
        val varsYaml = if (vars.isEmpty()) {
            "{}"
        } else {
            vars.joinToString("\n          ")
        }
        if (vpcConfig != null && (vpcSecurityGroupIdsParamPresent || vpcSubnetIdsParamPresent)) {
            throw IllegalArgumentException("Provide the VPC configuration using ApplicationConfig.vpcConfig " +
                "or by passing parameters VpcSubnetIds and VpcSecurityGroupsIds to the ApiStack template in " +
                "root.template. Using both at the same time is not supported")
        }
        val vpcConfigYaml: String = if (vpcSubnetIdsParamPresent) {
            """
            |      VpcConfig:
            |        SecurityGroupIds: !Split [",", !Ref VpcSecurityGroupIds]
            |        SubnetIds: !Split [",", !Ref VpcSubnetIds]
""".trimMargin()
        } else if (vpcConfig != null) {
            """
            |      VpcConfig:
            |        SecurityGroupIds: ${vpcConfig.securityGroupsIds.joinToString(",", "[", "]")}
            |        SubnetIds: ${vpcConfig.subnetIds.joinToString(",", "[", "]")}
""".trimMargin()
        } else {
            ""
        }
        @Language("yaml")
        val template = """
        |
        |  Function:
        |    Type: AWS::Lambda::Function
        |    Properties:
        |      $fnName
        |      Handler: $lambdaHandler
        |      Runtime: java8
        |      MemorySize: $memorySize
        |      Timeout: $timeout
        |      Environment:
        |        Variables:
        |          $varsYaml
        |      Code:
        |        S3Bucket: $codeS3Bucket
        |        S3Key: $codeS3Key
        |      Role: !Ref LambdaRole
        |$vpcConfigYaml
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Defines a role used by API Gateway when serving static files from S3.
 */
internal class StaticFilesRoleTemplate(private val staticFilesBucketArn: String) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
        |
        |  StaticFilesRole:
        |    Type: AWS::IAM::Role
        |    Properties:
        |      AssumeRolePolicyDocument:
        |        Version: 2012-10-17
        |        Statement:
        |          - Effect: Allow
        |            Principal:
        |              Service:
        |                - apigateway.amazonaws.com
        |            Action: sts:AssumeRole
        |      Policies:
        |        - PolicyName: StaticFilesPolicy
        |          PolicyDocument:
        |            Version: 2012-10-17
        |            Statement:
        |              - Effect: Allow
        |                Action:
        |                  - "s3:ListBucket"
        |                Resource: "$staticFilesBucketArn"
        |              - Effect: Allow
        |                Action:
        |                  - "s3:GetObject"
        |                Resource: "$staticFilesBucketArn/*"
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class DeploymentTemplate() {

    val resourceCount = 1

    fun write(writer: Writer, nestedStackIds: List<String>, rootResourceTemplate: ResourceTemplate) {
        // the deployment has to depend on every resource method in the API to avoid a race condition (?!)
        val methodIds = rootResourceTemplate.treeSequence().flatMap { it.methods.asSequence() }.map { it.name }
        // the nested stacks contain REST API resources so this need to depend on those too
        val dependencyIds = methodIds + nestedStackIds
        val dependencies = dependencyIds.joinToString("\n      - ")
        @Language("yaml")
        val template = """
        |
        |  Deployment:
        |    Type: AWS::ApiGateway::Deployment
        |    DependsOn:
        |      - $dependencies
        |    Properties:
        |      RestApiId: !Ref Api
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class StageTemplate(private val stage: Stage) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        // TODO escape the values
        val variables = when (stage.variables.isEmpty()) {
            false -> stage.variables.map { (k, v) -> "\"$k\": \"$v\"" }.joinToString("\n        ", "\n        ")
            true -> "{}"
        }
        @Language("yaml")
        val template = """
        |
        |  Stage${stage.name}:
        |    Type: AWS::ApiGateway::Stage
        |    Properties:
        |      StageName: ${stage.name}
        |      RestApiId: !Ref Api
        |      Description: "${stage.description}"
        |      DeploymentId: !Ref Deployment
        |      Variables: $variables
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class S3BucketTemplate(private val name: String) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
        |
        |  StaticFilesBucket:
        |    Type: AWS::S3::Bucket
        |    Properties:
        |      BucketName: $name
""".trimMargin()
        writer.write(template)
    }

}

//--------------------------------------------------------------------------------------------------

/**
 * Template for the parameters block in the CloudFormation file for the REST API.
 */
internal class ParametersTemplate(
    cognitoAuthParamRequired: Boolean,
    customAuthParamRequired: Boolean,
    templateParams: Set<String>
) : Template {

    override val resourceCount = 0

    /** Flag indicating whether the user manually passed a VpcSubnetIds parameter to the generated template. */
    internal val vpcSubnetIdsParamPresent: Boolean

    /** Flag indicating whether the user manually passed a VpcSecurityGroupsIds parameter to the generated template. */
    internal val vpcSecurityGroupIdsParamPresent: Boolean

    private val parameters: List<Parameter>

    init {
        log.debug("Creating ParametersTemplate, templateParams: {}", templateParams)
        val parametersBuilder = mutableListOf(lambdaRoleParam)
        vpcSecurityGroupIdsParamPresent = templateParams.contains(vpcSecurityGroupsIdsParam.name)
        vpcSubnetIdsParamPresent = templateParams.contains(vpcSubnetIdsParam.name)
        if (customAuthParamRequired) parametersBuilder.add(customAuthParam)
        if (cognitoAuthParamRequired) parametersBuilder.add(cognitoUserPoolParam)
        if (vpcSubnetIdsParamPresent) parametersBuilder.add(vpcSubnetIdsParam)
        if (vpcSecurityGroupIdsParamPresent) parametersBuilder.add(vpcSecurityGroupsIdsParam)
        val envVarParams = templateParams - vpcSubnetIdsParam.name - vpcSecurityGroupsIdsParam.name
        envVarParams.forEach {
            val param = Parameter(it, "String", "Environment variable '$it' passed from the parent template")
            parametersBuilder.add(param)
        }
        parameters = parametersBuilder.toList()
        if (vpcSecurityGroupIdsParamPresent && !vpcSubnetIdsParamPresent) {
            throw IllegalArgumentException("VpcSecurityGroupsIds found in root.template but not VpcSubnetIds. Both" +
                "must be specified if either is")
        }
        if (!vpcSecurityGroupIdsParamPresent && vpcSubnetIdsParamPresent) {
            throw IllegalArgumentException("VpcSubnetIds found in root.template but not VpcSecurityGroupsIds. Both" +
                "must be specified if either is")
        }
    }

    override fun write(writer: Writer) {
        if (parameters.isEmpty()) return
        writer.write("\n")
        writer.write("Parameters:\n")
        parameters.forEach { it.write(writer) }
        writer.write("\n")
    }

    private data class Parameter(val name: String, val type: String, val description: String) : Template {

        override val resourceCount = 0

        override fun write(writer: Writer) {
            writer.write("\n")
            writer.write("  $name:\n")
            writer.write("    Type: $type\n")
            writer.write("    Description: $description\n")
        }
    }

    companion object {
        private val lambdaRoleParam = Parameter(
            "LambdaRole",
            "String",
            "The ARN of the role assumed by the lambda when handling requests"
        )
        private val cognitoUserPoolParam = Parameter(
            "CognitoUserPoolArn",
            "String",
            "The ARN of the Cognito User Pool used by the authoriser"
        )
        private val customAuthParam = Parameter(
            "CustomAuthArn",
            "String",
            "The ARN of the custom authorization lambda function"
        )
        private val vpcSubnetIdsParam = Parameter(
            "VpcSubnetIds",
            "String",
            "The IDs of the subnets to which the application requires access"
        )
        private val vpcSecurityGroupsIdsParam = Parameter(
            "VpcSecurityGroupIds",
            "String",
            "The IDs of the subnets to which the application requires access"
        )
    }
}

//--------------------------------------------------------------------------------------------------

internal class CognitoAuthorizerTemplate(private val authConfig: AuthConfig?) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        val arn = if (authConfig is AuthConfig.CognitoUserPools) {
            log.debug("Creating Cognito authorizer resource with ARN of existing user pool {}", authConfig.userPoolArn)
            authConfig.userPoolArn
        } else {
            log.debug("Creating Cognito authorizer resource using template parameter CognitoUserPoolArn")
            "!Ref CognitoUserPoolArn"
        }
        @Language("yaml")
        val template = """
        |
        |  Authorizer:
        |    Type: "AWS::ApiGateway::Authorizer"
        |    Properties:
        |      Name: CognitoAuthorizer
        |      IdentitySource: method.request.header.Authorization
        |      ProviderARNs:
        |        - $arn
        |      RestApiId: !Ref Api
        |      Type: COGNITO_USER_POOLS
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class CustomAuthorizerTemplate(private val authConfig: AuthConfig?) : Template {

    override val resourceCount = 1

    override fun write(writer: Writer) {
        val arn = if (authConfig is AuthConfig.Custom) {
            log.debug("Creating custom authorizer resource with ARN of existing lambda {}", authConfig.lambdaArn)
            authConfig.lambdaArn
        } else {
            log.debug("Creating custom authorizer resource using template parameter CustomAuthArn")
            "\${CustomAuthArn}"
        }
        val uri = "!Sub arn:aws:apigateway:\${AWS::Region}:lambda:path/2015-03-31/functions/$arn/invocations"
        @Language("yaml")
        val template = """
        |
        |  Authorizer:
        |    Type: "AWS::ApiGateway::Authorizer"
        |    Properties:
        |      Name: CustomAuthorizer
        |      IdentitySource: method.request.header.Authorization
        |      AuthorizerUri: $uri
        |      RestApiId: !Ref Api
        |      Type: TOKEN
""".trimMargin()
        writer.write(template)
    }
}

// --------------------------------------------------------------------------------------------------

internal class RestStackTemplate(
    val name: String,
    private val stackFileName: String,
    private val codeBucket: String,
    private val authorizerParam: Boolean
) {

    fun write(writer: Writer) {
        val templateUrl = "https://$codeBucket.s3.\${AWS::Region}.amazonaws.com/$stackFileName"
        @Language("yaml")
        val template = """
        |
        |  $name:
        |    Type: AWS::CloudFormation::Stack
        |    Properties:
        |      TemplateURL: !Sub "$templateUrl"
        |      Parameters:
        |        ParentResourceId: !GetAtt Api.RootResourceId
        |        Api: !Ref Api
        |        LambdaArn: !GetAtt LambdaVersion.FunctionArn
        """.trimMargin()
        writer.write(template)
        if (authorizerParam) {
            writer.write("\n")
            writer.write("        Authorizer: !Ref Authorizer")
            writer.write("\n")
        }
    }
}

//--------------------------------------------------------------------------------------------------

internal class OutputsTemplate(
    private val codeS3Bucket: String,
    private val codeS3Key: String,
    private val authorizer: Boolean,
    private val keepAlive: Boolean
) : Template {

    override val resourceCount = 0

    override fun write(writer: Writer) {
        if (authorizer) log.debug("Creating template output AuthorizerId containing the ARN of the custom authorizer")
        val authTemplate = if (authorizer) """
        |  AuthorizerId:
        |    Description: ID of the authorizer
        |    Value: !Ref Authorizer
""" else ""
        val keepAliveTemplate = if (keepAlive) """
        |  KeepAliveLambdaArn:
        |    Description: The keep-alive lambda function
        |    Value: !GetAtt KeepAliveFunction.Arn
""" else ""
        @Language("yaml")
        val template = """
        |
        |Outputs:
        |  ApiId:
        |    Description: ID of the API Gateway API
        |    Value: !Ref Api
        |  ApiRootResourceId:
        |    Description: ID of the root resource API Gateway API
        |    Value: !GetAtt Api.RootResourceId
        |  LambdaArn:
        |    Description: The lambda function
        |    Value: !GetAtt Function.Arn
        |  LambdaVersionArn:
        |    Description: The lambda function version
        |    Value: !GetAtt LambdaVersion.FunctionArn
        |  CodeS3Bucket:
        |    Description: The name of the bucket containing the code
        |    Value: $codeS3Bucket
        |  CodeS3Key:
        |    Description: The key used to store the jar file containing the code in the S3 bucket
        |    Value: $codeS3Key$authTemplate$keepAliveTemplate
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class KeepAliveTemplate(
    private val instanceCount: Int,
    private val keepAliveInterval: Duration,
    private val keepAliveSleep: Duration,
    private val codeS3Bucket: String,
    private val codeS3Key: String
) : Template {

    override val resourceCount = 4

    override fun write(writer: Writer) {
        val intervalMinutes = keepAliveInterval.toMinutes()
        val scheduleExpr = if (intervalMinutes == 1L) "rate(1 minute)" else "rate($intervalMinutes minutes)"
        val targetId = UUID.randomUUID().toString()
        @Language("yaml")
        val template = """
        |
        |  KeepAliveEventRule:
        |    Type: AWS::Events::Rule
        |    Properties:
        |      Description: Event to trigger the keep-alive lambda to send keep-alive messages to the handler lambda
        |      ScheduleExpression: $scheduleExpr
        |      State: ENABLED
        |      Targets:
        |        - Arn: !GetAtt KeepAliveFunction.Arn
        |          Id: $targetId
        |          Input: !Sub |
        |            {
        |              "functionArn": "${'$'}{LambdaVersion.FunctionArn}",
        |              "instanceCount": $instanceCount,
        |              "sleepTimeMs": ${keepAliveSleep.toMillis()}
        |            }
        |
        |  KeepAliveFunction:
        |    Type: AWS::Lambda::Function
        |    Properties:
        |      Handler: ws.osiris.aws.KeepAliveLambda::handle
        |      Runtime: java8
        |      MemorySize: 1024
        |      Timeout: 300
        |      Code:
        |        S3Bucket: $codeS3Bucket
        |        S3Key: $codeS3Key
        |      Role: !GetAtt KeepAliveFunctionRole.Arn
        |
        |  KeepAliveFunctionRole:
        |    Type: AWS::IAM::Role
        |    Properties:
        |      AssumeRolePolicyDocument:
        |        Version: 2012-10-17
        |        Statement:
        |          - Effect: Allow
        |            Principal:
        |              Service:
        |                - lambda.amazonaws.com
        |            Action: sts:AssumeRole
        |      Policies:
        |        - PolicyName: LambdaPolicy
        |          PolicyDocument:
        |            Version: 2012-10-17
        |            Statement:
        |              - Effect: Allow
        |                Action:
        |                  - "logs:*"
        |                Resource: "arn:aws:logs:*:*:*"
        |              - Effect: Allow
        |                Action:
        |                  - "lambda:InvokeFunction"
        |                Resource: !GetAtt LambdaVersion.FunctionArn
        |
        |  KeepAlivePermission:
        |    Type: AWS::Lambda::Permission
        |    Properties:
        |      Action: lambda:InvokeFunction
        |      FunctionName: !GetAtt KeepAliveFunction.Arn
        |      Principal: events.amazonaws.com
        |      SourceArn: !GetAtt KeepAliveEventRule.Arn
""".trimMargin()
        writer.write(template)
    }

}

//--------------------------------------------------------------------------------------------------

/**
 * This is a diabolical hack needed because CloudFormation's support for lambda versions is essentially useless.
 *
 * It defines a lambda function that publishes a new version of another lambda. It also defines a custom
 * resource for the lambda that needs to be published that invokes the publication lambda when the
 * CloudFormation stack is updated.
 *
 * The hash of the code jar is used as a property of the custom resource. This ensures the custom resource
 * changes when the code changes and is therefore republished by CloudFormation.
 *
 * See:
 * * [https://github.com/awslabs/serverless-application-model/issues/41#issuecomment-315147991]
 * * [https://stackoverflow.com/questions/41452274/how-to-create-a-new-version-of-a-lambda-function-using-cloudformation/41455577#41455577]
 */
internal class PublishLambdaTemplate(private val codeHash: String) : Template {

    override val resourceCount = 3

    override fun write(writer: Writer) {
        @Language("NONE") // IntelliJ is convinced this is ES6 for some reason
        val arn = "arn:aws:execute-api:\${AWS::Region}:\${AWS::AccountId}:\${Api}/*"
        val statementId = UUID.randomUUID().toString()
        @Language("ES6")
        val script = """
          var AWS = require('aws-sdk');
          var response = require('cfn-response');
          exports.handler = (event, context, callback) => {
            if (event.RequestType == 'Delete') {
              response.send(event, context, response.SUCCESS);
            }
            var lambda = new AWS.Lambda();
            lambda.publishVersion({FunctionName: event.ResourceProperties.FunctionName}).promise().then((data) => {
              var permissionsParams = {
                  Action: "lambda:InvokeFunction",
                  FunctionName: data.FunctionArn,
                  Principal: "apigateway.amazonaws.com",
                  SourceArn: "$arn",
                  StatementId: "$statementId"
              }
              lambda.addPermission(permissionsParams).promise().then((resp) => {
                return response.send(event, context, response.SUCCESS, {FunctionArn: data.FunctionArn}, data.FunctionArn);
              }).catch((e) => {
                return response.send(event, context, response.FAILED, e);
              });
            }).catch((e) => {
              return response.send(event, context, response.FAILED, e);
            });
          };
"""

        @Language("yaml")
        val template = """
        |
        |  LambdaVersion:
        |    Type: Custom::LambdaVersion
        |    Properties:
        |      ServiceToken: !GetAtt LambdaVersionFunction.Arn
        |      FunctionName: !Ref Function
        |      CodeHash: $codeHash
        |
        |  LambdaVersionFunction:
        |    Type: AWS::Lambda::Function
        |    Properties:
        |      Handler: "index.handler"
        |      Role: !GetAtt LambdaVersionExecutionRole.Arn
        |      Code:
        |        ZipFile: !Sub |
        |          $script
        |      Runtime: nodejs8.10
        |
        |  LambdaVersionExecutionRole:
        |    Type: AWS::IAM::Role
        |    Properties:
        |      AssumeRolePolicyDocument:
        |        Version: '2012-10-17'
        |        Statement:
        |        - Effect: Allow
        |          Principal: {Service: [lambda.amazonaws.com]}
        |          Action: ['sts:AssumeRole']
        |      Path: /
        |      ManagedPolicyArns:
        |      - arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
        |      Policies:
        |      - PolicyName: PublishVersion
        |        PolicyDocument:
        |          Version: 2012-10-17
        |          Statement:
        |          - Effect: Allow
        |            Action: ['lambda:PublishVersion', 'lambda:AddPermission']
        |            Resource: '*'
""".trimMargin()
        writer.write(template)
    }
}

private fun authSnippet(auth: Auth?): String = if (auth is CustomAuth || auth is CognitoUserPoolsAuth) {
    "AuthorizationType: ${auth.name}\n        |      AuthorizerId: !Ref Authorizer"
} else {
    "AuthorizationType: ${(auth ?: NoAuth).name}"
}
