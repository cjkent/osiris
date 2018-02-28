package io.github.cjkent.osiris.aws

import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ComponentsProvider
import java.time.Duration

/**
 * The configuration of a deployed application.
 *
 * The values in this class control how the application is deployed to AWS.
 */
data class ApplicationConfig(
    /** The name of the API in API Gateway and the stack in CloudFormation. */
    val applicationName: String,

    /** A description of the application. */
    val applicationDescription: String = "Application '$applicationName', created with Osiris",

    /** The maximum memory available to the lambda containing the handler code. */
    val lambdaMemorySizeMb: Int = 512,

    /** The maximum time the handler code can run before being terminated by AWS. */
    val lambdaTimeout: Duration = Duration.ofSeconds(10),

    /** The environment variables available to the lambda code; used when creating components. */
    val environmentVariables: Map<String, String> = mapOf(),

    /** The stages to which the API is deployed, for example `dev`, `test` and `prod`. */
    val stages: List<Stage> = listOf(),

    /**
     * Configuration of any external authentication mechanism.
     *
     * This is only necessary if the auth mechanism is _not_ created as part of the same CloudFormation
     * stack as the application. If the Cognito user pool or custom auth lambda are defined in the
     * application stack then this property should not be set.
     */
    val authConfig: AuthConfig? = null,

    /** The bucket from which static files are served; if this is not specified a bucket is created. */
    val staticFilesBucket: String? = null,

    /** The bucket to which code artifacts are uploaded; if this is not specified a bucket is created. */
    val codeBucket: String? = null

)

/**
 * Configuration of the authentication mechanism.
 *
 * This is only required if the authentication mechanism uses an existing AWS resources; a Cognito user
 * pool or a custom auth lambda function. If the user pool or lambda are created as part of the same
 * CloudFormation stack as the API and handlers then no configuration is necessary.
 *
 * The `AuthConfig` must match the auth configuration of the API endpoints,
 * [CognitoUserPoolsAuth] or [CustomAuth].
 */
sealed class AuthConfig {

    /**
     * Represents a custom authentication lambda function.
     *
     * See [here](http://docs.aws.amazon.com/apigateway/latest/developerguide/use-custom-authorizer.html)
     * for more information about custom authentication.
     *
     * This should only be used if the custom authentication lambda is _not_ created as part of the same
     * CloudFormation stack as the application.
     */
    data class Custom(
        /** The ARN of the custom authentication lambda function. */
        val lambdaArn: String
    ) : AuthConfig()

    /**
     * Represents a Cognito user pool used for authentication.
     *
     * See [here](http://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-integrate-with-cognito.html)
     * for more information about using Cognito user pools for authentication.
     *
     * This should only be used if the user pool is _not_ created as part of the same
     * CloudFormation stack as the application.
     */
    data class CognitoUserPools(
        /** The ARN of the Cognito user pool. */
        val userPoolArn: String
    ) : AuthConfig()
}

/**
 * Represents a single stage in API Gateway.
 *
 * A stage represents a deployed snapshot of an API. An API must be deployed to a stage before
 * it can be used.
 *
 * Is is common to have multiple stages representing different points in the
 * application lifecycle. For example, an application might be deployed to the `dev` stage
 * whenever any code changes are made. It might be deployed to the `test` stages for testing
 * before release. And finally it might be deployed to the `prod` stage one it is fully tested
 * and ready for release.
 *
 * See [here](http://docs.aws.amazon.com/apigateway/latest/developerguide/how-to-deploy-api.html) for
 * more information about stages.
 */
data class Stage(
    /** The name of the stage. */
    val name: String,

    /**
     * Flag controlling whether the API is deployed to the stage every time it is updated.
     *
     * If this is true, the API is deployed to the stage whenever `mvn deploy` is executed.
     * If this is false, the API is only deployed to the stage when it is created. Subsequent
     * updates are not deployed automatically and the API is only deployed to the stage by
     * some manual process.
     *
     * This is likely to be true for development stages and false for production stages.
     */
    val deployOnUpdate: Boolean,

    /** The stage variables; these are environment variables that can have different values in each stage. */
    val variables: Map<String, String> = mapOf(),

    /** A description of the stage. */
    val description: String = "Stage '$name'"
)

// TODO move this to a better place
/**
 * Interface implemented by the generated code that creates the API.
 *
 * The implementation is created reflectively.
 */
interface ApiFactory<T : ComponentsProvider> {

    /** The API. */
    val api: Api<T>

    /** The configuration of the application in AWS. */
    val config: ApplicationConfig
}
