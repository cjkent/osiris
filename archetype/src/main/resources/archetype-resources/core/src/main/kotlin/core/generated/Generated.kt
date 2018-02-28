package ${package}.core.generated

import io.github.cjkent.osiris.aws.ApiFactory
import io.github.cjkent.osiris.aws.ApplicationConfig
import io.github.cjkent.osiris.aws.ProxyLambda
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ComponentsProvider

import ${package}.core.api
import ${package}.core.createComponents

/**
 * The lambda function that is deployed to AWS and provides the entry point to the application.
 *
 * This is generated code and should not be modified or deleted.
 */
@Suppress("UNCHECKED_CAST", "unused")
class GeneratedLambda : ProxyLambda<ComponentsProvider>(api as Api<ComponentsProvider>, createComponents())

/**
 * Creates the API and application configuration; used by the build plugins during deployment.
 *
 * This is generated code and should not be modified or deleted.
 */
@Suppress("UNCHECKED_CAST", "unused")
class GeneratedApiFactory<T : ComponentsProvider> : ApiFactory<T> {
    override val api: Api<T> = ${package}.core.api as Api<T>
    override val config: ApplicationConfig = ${package}.core.config
}
