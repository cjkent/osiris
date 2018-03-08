package io.github.cjkent.osiris.awsdeploy.cloudformation

import com.google.common.hash.Hashing
import io.github.cjkent.osiris.aws.AuthConfig
import io.github.cjkent.osiris.aws.CognitoUserPoolsAuth
import io.github.cjkent.osiris.aws.CustomAuth
import io.github.cjkent.osiris.aws.Stage
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.Auth
import io.github.cjkent.osiris.core.FixedRouteNode
import io.github.cjkent.osiris.core.HttpMethod
import io.github.cjkent.osiris.core.NoAuth
import io.github.cjkent.osiris.core.RouteNode
import io.github.cjkent.osiris.core.StaticRouteNode
import io.github.cjkent.osiris.core.VariableRouteNode
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.io.Writer
import java.lang.Long
import java.util.UUID

private val log = LoggerFactory.getLogger("io.github.cjkent.osiris.awsdeploy.cloudformation")

interface WritableResource {
    fun write(writer: Writer)
}

//--------------------------------------------------------------------------------------------------

internal class ApiTemplate(
    private val name: String,
    private val description: String?,
    private val envName: String?,
    internal val rootResource: ResourceTemplate
) : WritableResource {

    override fun write(writer: Writer) {
        val name = if (envName == null) this.name else "${this.name}.$envName"
        @Language("yaml")
        val template = """
        |
        |  Api:
        |    Type: AWS::ApiGateway::RestApi
        |    Properties:
        |      Name: "$name"
        |      Description: "${description ?: name}"
        |      FailOnWarnings: true
""".trimMargin()
        writer.write(template)
        rootResource.write(writer)
    }

    companion object {

        fun create(
            api: Api<*>,
            name: String,
            description: String?,
            envName: String?,
            staticFilesBucket: String,
            staticHash: String?
        ): ApiTemplate {

            val rootNode = RouteNode.create(api)
            val rootTemplate = resourceTemplate(rootNode, staticFilesBucket, staticHash, true, "", "")
            return ApiTemplate(name, description, envName, rootTemplate)
        }

        // 3 things are needed for each resource template
        //   1) resource name
        //   2) ref to parent
        //   3) ref used by methods and children
        //
        // 3 cases:
        //   root node
        //     1) generated from ID
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
            staticFilesBucket: String,
            staticHash: String?,
            isRoot: Boolean,
            parentRef: String,
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
            val fixedChildTemplates = fixedChildResourceTemplates(node, resourceRef, staticFilesBucket, staticHash, path)
            val staticProxyTemplates = staticProxyResourceTemplates(node, resourceRef, staticFilesBucket, staticHash, path)
            val variableChildTemplates = variableChildResourceTemplates(node, resourceRef, staticFilesBucket, staticHash, path)
            val childResourceTemplates = fixedChildTemplates + variableChildTemplates + staticProxyTemplates
            return ResourceTemplate(methods, resourceName, pathPart, childResourceTemplates, isRoot, parentRef)
        }

        /**
         * Creates resource templates for the fixed children of the node.
         */
        private fun fixedChildResourceTemplates(
            node: RouteNode<*>,
            resourceRef: String,
            staticFilesBucket: String,
            staticHash: String?,
            parentPath: String
        ): List<ResourceTemplate> = node.fixedChildren.values.map {
            resourceTemplate(it, staticFilesBucket, staticHash, false, resourceRef, parentPath)
        }

        /**
         * Creates resource templates for the fixed children of the node.
         */
        private fun variableChildResourceTemplates(
            node: RouteNode<*>,
            resourceRef: String,
            staticFilesBucket: String,
            staticHash: String?,
            parentPath: String
        ): List<ResourceTemplate> {
            val variableChild = node.variableChild
            return when (variableChild) {
                null -> listOf()
                else -> listOf(resourceTemplate(variableChild, staticFilesBucket, staticHash, false, resourceRef, parentPath))
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
            resourceRef: String,
            staticFilesBucket: String,
            staticHash: String?,
            parentPath: String
        ): List<ResourceTemplate> = if (node is StaticRouteNode<*>) {
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
            listOf(ResourceTemplate(proxyChildren, proxyChildName, pathPart, listOf(), false, resourceRef))
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
            staticFilesBucket: String,
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
            staticFilesBucket: String,
            staticHash: String?
        ): List<MethodTemplate> {

            log.debug("Creating static index file template with hash {}, bucket {}", staticHash, staticFilesBucket)
            val indexFile = node.indexFile
            return if (indexFile == null) {
                listOf()
            } else {
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

internal class ResourceTemplate(
    internal val methods: List<MethodTemplate>,
    private val name: String,
    private val pathPart: String,
    private val children: List<ResourceTemplate>,
    private val isRoot: Boolean,
    private val parentResourceRef: String
) : WritableResource {

    override fun write(writer: Writer) {
        // don't need to create a CF resource for the root API gateway resource, it's always there
        if (!isRoot) {
            @Language("yaml")
            val template = """
            |
            |  $name:
            |    Type: AWS::ApiGateway::Resource
            |    Properties:
            |      RestApiId: !Ref Api
            |      ParentId: $parentResourceRef
            |      PathPart: "$pathPart"
""".trimMargin()
            writer.write(template)
        }
        for (method in methods) {
            method.write(writer)
        }
        for (child in children) {
            child.write(writer)
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

sealed class MethodTemplate : WritableResource {
    abstract internal val name: String
}

//--------------------------------------------------------------------------------------------------

internal class LambdaMethodTemplate(
    resourceName: String,
    private val resourceRef: String,
    private val httpMethod: HttpMethod,
    private val auth: Auth?
) : MethodTemplate() {

    override val name = "$resourceName$httpMethod"

    override fun write(writer: Writer) {
        // this is easier to do in a regular string because the ${} can be escaped
        val uri = "arn:aws:apigateway:\${AWS::Region}:lambda:path/2015-03-31/functions/\${LambdaVersion.FunctionArn}/invocations"
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

    override val name: String = "${resourceName}GET"

    override fun write(writer: Writer) {
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

    override val name: String = "${resourceName}GET"

    override fun write(writer: Writer) {
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
    private val lambdaHandler: String,
    private val memorySize: Int,
    private val timeout: Int,
    private val codeS3Bucket: String,
    private val codeS3Key: String,
    private val envVars: Map<String, String>,
    private val templateParams: Set<String>,
    private val envName: String?,
    createRole: Boolean
) : WritableResource {

    private val role = if (createRole) "!GetAtt FunctionRole.Arn" else "!Ref LambdaRole"

    override fun write(writer: Writer) {
        // TODO escape the values
        val userVars = envVars.map { (k, v) -> "$k: \"$v\"" }
        val templateVars = templateParams.map { "$it: !Ref $it" }
        val envNameVar = envName?.let { "ACCOUNT_NAME: \"$envName\"" } ?: ""
        val vars = userVars + templateVars + envNameVar
        val varsYaml = if (vars.isEmpty()) {
            "{}"
        } else {
            vars.joinToString("\n          ")
        }
        @Language("yaml")
        val template = """
        |
        |  Function:
        |    Type: AWS::Lambda::Function
        |    Properties:
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
        |      Role: $role
""".trimMargin()
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Defines a role used by the lambda; grants permissions to write logs.
 */
internal class LambdaRoleTemplate : WritableResource {

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
        |
        |  FunctionRole:
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
""".trimMargin()
        writer.write(template)
    }
}

/**
 * Defines a role used by API Gateway when serving static files from S3.
 */
internal class StaticFilesRoleTemplate(private val staticFilesBucketArn: String) : WritableResource {

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

internal class DeploymentTemplate(private val apiTemplate: ApiTemplate) : WritableResource {

    override fun write(writer: Writer) {
        // the deployment has to depend on every resource method in the API to avoid a race condition (?!)
        val dependencies = apiTemplate.rootResource.treeSequence()
            .flatMap { it.methods.asSequence() }
            .map { it.name }
            .joinToString("\n      - ")
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

internal class StageTemplate(private val stage: Stage) : WritableResource {

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

internal class S3BucketTemplate(private val name: String) : WritableResource {

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

internal class ParametersTemplate(
    lambdaParameter: Boolean,
    cognitoAuthParamRequired: Boolean,
    customAuthParamRequired: Boolean,
    templateParams: Set<String>
) : WritableResource {

    private val parameters: List<Parameter>

    init {
        val parametersBuilder = mutableListOf<Parameter>()
        if (lambdaParameter) parametersBuilder.add(lambdaRoleParam)
        if (customAuthParamRequired) parametersBuilder.add(customAuthParam)
        if (cognitoAuthParamRequired) parametersBuilder.add(cognitoUserPoolParam)
        templateParams.forEach {
            val param = Parameter(it, "String", "Environment variable '$it' passed from the parent template")
            parametersBuilder.add(param)
        }
        parameters = parametersBuilder.toList()
    }

    override fun write(writer: Writer) {
        if (parameters.isEmpty()) return
        writer.write("\n")
        writer.write("Parameters:\n")
        parameters.forEach { it.write(writer) }
        writer.write("\n")
    }

    private data class Parameter(val name: String, val type: String, val description: String) : WritableResource {

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
    }
}

//--------------------------------------------------------------------------------------------------

internal class CognitoAuthorizerTemplate(private val authConfig: AuthConfig?) : WritableResource {

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

internal class CustomAuthorizerTemplate(private val authConfig: AuthConfig?) : WritableResource {

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

//--------------------------------------------------------------------------------------------------

internal class OutputsTemplate(
    private val codeS3Bucket: String,
    private val codeS3Key: String,
    private val authorizer: Boolean
) : WritableResource {

    override fun write(writer: Writer) {
        if (authorizer) log.debug("Creating template output AuthorizerId containing the ARN of the custom authorizer")
        @Language("yaml")
        val authTemplate = if (authorizer) """
        |  AuthorizerId:
        |    Description: ID of the authorizer
        |    Value: !Ref Authorizer
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
        |    Value: $codeS3Key$authTemplate
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
internal class PublishLambdaTemplate(private val codeHash: String) : WritableResource {

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
        |      Runtime: nodejs6.10
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
