package io.github.cjkent.osiris.aws.cloudformation

import io.github.cjkent.osiris.aws.Stage
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.Auth
import io.github.cjkent.osiris.core.FixedRouteNode
import io.github.cjkent.osiris.core.HttpMethod
import io.github.cjkent.osiris.core.RouteNode
import io.github.cjkent.osiris.core.StaticRouteNode
import io.github.cjkent.osiris.core.VariableRouteNode
import org.intellij.lang.annotations.Language
import java.io.Writer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

interface WritableResource {
    fun write(writer: Writer)
}

//--------------------------------------------------------------------------------------------------

internal class ApiTemplate(
    private val name: String,
    private val description: String?,
    internal val rootResource: ResourceTemplate
) : WritableResource {

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
  Api:
    Type: AWS::ApiGateway::RestApi
    Properties:
      Name: $name
      Description: "${description ?: name}"
      FailOnWarnings: true
"""
        writer.write(template)
        rootResource.write(writer)
    }

    companion object {

        /** Incrementing ID for the resources; I don't like it but it's simple and I don't care enough to change it. */
        private val resourceId = AtomicInteger()

        fun create(
            api: Api<*>,
            name: String,
            description: String?,
            staticFilesBucket: String,
            roleArn: String?
        ): ApiTemplate {

            val rootNode = RouteNode.create(api)
            val rootTemplate = resourceTemplate(rootNode, staticFilesBucket, roleArn, true, "")
            return ApiTemplate(name, description, rootTemplate)
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
            roleArn: String?,
            isRoot: Boolean,
            parentRef: String
        ): ResourceTemplate {

            val id = resourceId.getAndIncrement()
            val resourceName = "Resource$id"
            // the reference used by methods and child resources to refer to the current resource
            val resourceRef = if (isRoot) "!GetAtt Api.RootResourceId" else "!Ref $resourceName"
            val methods = methodTemplates(node, resourceName, resourceRef, staticFilesBucket, roleArn)
            val fixedChildTemplates = fixedChildResourceTemplates(node, resourceRef, staticFilesBucket, roleArn)
            val staticProxyTemplates = staticProxyResourceTemplates(node, resourceRef, staticFilesBucket, roleArn)
            val variableChildTemplates = variableChildResourceTemplates(node, resourceRef, staticFilesBucket, roleArn)
            val childResourceTemplates = fixedChildTemplates + variableChildTemplates + staticProxyTemplates
            val pathPart = when (node) {
                is VariableRouteNode<*> -> "{${node.name}}"
                else -> node.name
            }
            return ResourceTemplate(resourceName, methods, pathPart, childResourceTemplates, isRoot, parentRef)
        }

        /**
         * Creates resource templates for the fixed children of the node.
         */
        private fun fixedChildResourceTemplates(
            node: RouteNode<*>,
            resourceRef: String,
            staticFilesBucket: String,
            roleArn: String?
        ): List<ResourceTemplate> = node.fixedChildren.values.map {
            resourceTemplate(it, staticFilesBucket, roleArn, false, resourceRef)
        }

        /**
         * Creates resource templates for the fixed children of the node.
         */
        private fun variableChildResourceTemplates(
            node: RouteNode<*>,
            resourceRef: String,
            staticFilesBucket: String,
            roleArn: String?
        ): List<ResourceTemplate> {
            val variableChild = node.variableChild
            return when (variableChild) {
                null -> listOf()
                else -> listOf(resourceTemplate(variableChild, staticFilesBucket, roleArn, false, resourceRef))
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
            roleArn: String?
        ): List<ResourceTemplate> = if (node is StaticRouteNode<*>) {
            val proxyChildName = "Resource${resourceId.getAndIncrement()}"
            val proxyChildRef = "!Ref $proxyChildName"
            val proxyChildMethodTemplate =
                StaticRootMethodTemplate(proxyChildName, proxyChildRef, node.auth, staticFilesBucket, roleArn)
            val proxyChildren = listOf(proxyChildMethodTemplate)
            listOf(ResourceTemplate(proxyChildName, proxyChildren, "{proxy+}", listOf(), false, resourceRef))
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
            roleArn: String?
        ): List<MethodTemplate> = when (node) {
            is FixedRouteNode<*> -> lambdaMethodTemplates(node, resourceName, resourceRef)
            is VariableRouteNode<*> -> lambdaMethodTemplates(node, resourceName, resourceRef)
            is StaticRouteNode<*> -> indexFileMethodTemplates(node, resourceName, resourceRef, staticFilesBucket, roleArn)
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
            roleArn: String?
        ): List<MethodTemplate> {

            val indexFile = node.indexFile
            return if (indexFile == null) {
                listOf()
            } else {
                listOf(
                    StaticIndexFileMethodTemplate(resourceName, resourceRef, node.auth, staticFilesBucket, indexFile, roleArn)
                )
            }
        }
    }
}

//--------------------------------------------------------------------------------------------------

internal class ResourceTemplate(
    internal val name: String,
    internal val methods: List<MethodTemplate>,
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
  $name:
    Type: AWS::ApiGateway::Resource
    Properties:
      RestApiId: !Ref Api
      ParentId: $parentResourceRef
      PathPart: "$pathPart"
"""
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
  $name:
    Type: AWS::ApiGateway::Method
    Properties:
      HttpMethod: $httpMethod
      ResourceId: $resourceRef
      RestApiId: !Ref Api
      AuthorizationType: ${(auth ?: Auth.None).name}
      Integration:
        IntegrationHttpMethod: POST
        Type: AWS_PROXY
        Uri: !Sub $uri
"""
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class StaticRootMethodTemplate(
    resourceName: String,
    private val resourceRef: String,
    private val auth: Auth?,
    private val staticFilesBucket: String,
    roleArn: String?
) : MethodTemplate() {

    override val name: String = "${resourceName}GET"

    private val roleArn: String = roleArn ?: "!GetAtt FunctionRole.Arn"

    override fun write(writer: Writer) {
        val arn = "arn:aws:apigateway:\${AWS::Region}:s3:path/$staticFilesBucket/{object}"
        @Language("yaml")
        val template = """
  $name:
    Type: AWS::ApiGateway::Method
    Properties:
      HttpMethod: GET
      ResourceId: $resourceRef
      RestApiId: !Ref Api
      AuthorizationType: ${(auth ?: Auth.None).name}
      RequestParameters:
        method.request.path.proxy: true
      Integration:
        IntegrationHttpMethod: GET
        Type: AWS
        Uri: !Sub $arn
        Credentials: $roleArn
        RequestParameters:
          integration.request.path.object: method.request.path.proxy
        IntegrationResponses:
          - StatusCode: 200
            ResponseParameters:
              method.response.header.Content-Type: integration.response.header.Content-Type
              method.response.header.Content-Length: integration.response.header.Content-Length
          - StatusCode: 403
            SelectionPattern: 403
          - StatusCode: 404
            SelectionPattern: 404
      MethodResponses:
        - StatusCode: 200
          ResponseParameters:
            method.response.header.Content-Type: true
            method.response.header.Content-Length: true
        - StatusCode: 403
        - StatusCode: 404
"""
        writer.write(template)
    }
}

internal class StaticIndexFileMethodTemplate(
    resourceName: String,
    private val resourceRef: String,
    private val auth: Auth?,
    private val staticFilesBucket: String,
    private val indexFile: String,
    roleArn: String?
) : MethodTemplate() {

    override val name: String = "${resourceName}GET"

    private val roleArn: String = roleArn ?: "!GetAtt FunctionRole.Arn"

    override fun write(writer: Writer) {
        val arn = "arn:aws:apigateway:\${AWS::Region}:s3:path/$staticFilesBucket/$indexFile"
        @Language("yaml")
        val template = """
  $name:
    Type: AWS::ApiGateway::Method
    Properties:
      HttpMethod: GET
      ResourceId: $resourceRef
      RestApiId: !Ref Api
      AuthorizationType: ${(auth ?: Auth.None).name}
      Integration:
        IntegrationHttpMethod: GET
        Type: AWS
        Uri: !Sub $arn
        Credentials: $roleArn
        IntegrationResponses:
          - StatusCode: 200
            ResponseParameters:
              method.response.header.Content-Type: integration.response.header.Content-Type
              method.response.header.Content-Length: integration.response.header.Content-Length
          - StatusCode: 403
            SelectionPattern: 403
          - StatusCode: 404
            SelectionPattern: 404
      MethodResponses:
        - StatusCode: 200
          ResponseParameters:
            method.response.header.Content-Type: true
            method.response.header.Content-Length: true
        - StatusCode: 403
        - StatusCode: 404
"""
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class LambdaTemplate(
    private val memorySize: Int,
    private val timeout: Int,
    private val componentsClass: KClass<*>,
    private val apiDefinitionClass: KClass<*>,
    private val codeS3Bucket: String,
    private val codeS3Key: String,
    private val envVars: Map<String, String>,
    role: String?
) : WritableResource {

    private val role = role ?: "!GetAtt FunctionRole.Arn"

    override fun write(writer: Writer) {
        // TODO escape the values
        val variables = envVars.map { (k, v) -> "$k: \"$v\"" }.joinToString("\n        ")
        @Language("yaml")
        val template = """
  Function:
    Type: AWS::Lambda::Function
    Properties:
      Handler: io.github.cjkent.osiris.aws.ProxyLambda::handle
      Runtime: java8
      MemorySize: $memorySize
      Timeout: $timeout
      Environment:
        Variables:
          API_COMPONENTS_CLASS: ${componentsClass.jvmName}
          API_DEFINITION_CLASS: ${apiDefinitionClass.jvmName}
          $variables
      Code:
        S3Bucket: $codeS3Bucket
        S3Key: $codeS3Key
      Role: $role
"""
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class RoleTemplate : WritableResource {

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
  FunctionRole:
    Type: AWS::IAM::Role
    Properties:
      AssumeRolePolicyDocument:
        Version: 2012-10-17
        Statement:
          - Effect: Allow
            Principal:
              Service:
                - lambda.amazonaws.com
                - apigateway.amazonaws.com
            Action: sts:AssumeRole
      ManagedPolicyArns:
        # todo this needs to be a policy with S3 list permissions so unknown static files return 404 not 403
        - arn:aws:iam::aws:policy/AWSLambdaExecute
"""
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

// TODO this doesn't work with stages
// when the API is updated this is updated to point to the new function version and the old permission
// no longer applies to the previous version
internal class RolePermissionTemplate : WritableResource {

    override fun write(writer: Writer) {
        val arn = "arn:aws:execute-api:\${AWS::Region}:\${AWS::AccountId}:\${Api}/*"
        @Language("yaml")
        val template = """
  FunctionRolePermission:
    Type: AWS::Lambda::Permission
    Properties:
      FunctionName: !GetAtt LambdaVersion.FunctionArn
      Action: lambda:InvokeFunction
      Principal: apigateway.amazonaws.com
      SourceArn:
        !Sub $arn
"""
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
  Deployment:
    Type: AWS::ApiGateway::Deployment
    DependsOn:
      - $dependencies
    Properties:
      RestApiId: !Ref Api
"""
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
  Stage${stage.name}:
    Type: AWS::ApiGateway::Stage
    Properties:
      StageName: ${stage.name}
      RestApiId: !Ref Api
      Description: "${stage.description}"
      DeploymentId: !Ref Deployment
      Variables: $variables
"""
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

internal class S3BucketTemplate(private val name: String) : WritableResource {

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
  StaticFilesBucket:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: $name
"""
        writer.write(template)
    }

}

//--------------------------------------------------------------------------------------------------

internal class OutputsTemplate : WritableResource {

    override fun write(writer: Writer) {
        @Language("yaml")
        val template = """
Outputs:
  ApiId:
    Description: ID of the API Gateway API
    Value: !Ref Api
  ApiRootResourceId:
    Description: ID of the root resource API Gateway API
    Value: !GetAtt Api.RootResourceId
  LambdaArn:
    Description: The lambda function
    Value: !GetAtt Function.Arn
  LambdaVersionArn:
    Description: The lambda function version
    Value: !GetAtt LambdaVersion.FunctionArn
"""
        writer.write(template)
    }
}

//--------------------------------------------------------------------------------------------------

// TODO is it possible to avoid using cfn-response?
// if I return a simple object from the function what happens to it? is it exposed as attributes of the custom resource?

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
        val arn = "arn:aws:execute-api:\${AWS::Region}:\${AWS::AccountId}:\${Api}/*"
        val statementId = UUID.randomUUID().toString()
        @Language("yaml")
        val template = """
  LambdaVersion:
    Type: Custom::LambdaVersion
    Properties:
      ServiceToken: !GetAtt LambdaVersionFunction.Arn
      FunctionName: !Ref Function
      CodeHash: $codeHash

  LambdaVersionFunction:
    Type: AWS::Lambda::Function
    Properties:
      Handler: "index.handler"
      Role: !GetAtt LambdaVersionExecutionRole.Arn
      Code:
        ZipFile: !Sub |
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
      Runtime: nodejs6.10

  LambdaVersionExecutionRole:
    Type: AWS::IAM::Role
    Properties:
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
        - Effect: Allow
          Principal: {Service: [lambda.amazonaws.com]}
          Action: ['sts:AssumeRole']
      Path: /
      ManagedPolicyArns:
      - arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
      Policies:
      - PolicyName: PublishVersion
        PolicyDocument:
          Version: 2012-10-17
          Statement:
          - Effect: Allow
            Action: ['lambda:PublishVersion', 'lambda:AddPermission']
            Resource: '*'
"""
        writer.write(template)
    }
}
