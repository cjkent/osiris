package io.github.cjkent.osiris.maven

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import io.github.cjkent.osiris.aws.Stage
import io.github.cjkent.osiris.aws.addPermissions
import io.github.cjkent.osiris.aws.createRole
import io.github.cjkent.osiris.aws.createStaticFilesBucket
import io.github.cjkent.osiris.aws.deployApi
import io.github.cjkent.osiris.aws.deployLambda
import io.github.cjkent.osiris.aws.uploadFile
import io.github.cjkent.osiris.core.Api
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.server.ApiFactory
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : AbstractMojo() {

    @Parameter(required = true)
    private lateinit var apiName: String

    // TODO can this be provided by the profile? it can definitely be specified. maybe it should be optional
    @Parameter(required = true)
    private lateinit var region: String

    @Parameter(required = true)
    private lateinit var apiDefinitionClass: String

    @Parameter(required = true)
    private lateinit var componentsClass: String

    @Parameter
    private var role: String? = null

    @Parameter(property = "awsProfile")
    private var awsProfile: String? = null

    @Parameter
    private var stages: Map<String, StageConfig> = mapOf()

    @Parameter
    private var environmentVariables: Map<String, String>? = null

    @Parameter
    private var lambdaMemorySize: Int = 512

    @Parameter
    private var lambdaTimeout: Int = 3

    @Parameter
    private var staticFilesBucket: String? = null

    @Parameter
    private var staticFilesDirectory: String? = null

    // This is the directory {project}/target/classes
    @Parameter(property = "project.build.outputDirectory")
    private lateinit var builtResourcesDirectory: File

    @Component
    private lateinit var project: MavenProject

    override fun execute() {
        val jarFile = "${project.build.directory}/${project.artifactId}-${project.version}-jar-with-dependencies.jar"
        val jarPath = Paths.get(jarFile)
        if (!Files.exists(jarPath)) throw MojoFailureException("Cannot find $jarFile")
        val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), javaClass.classLoader)
        val apiFactory = ApiFactory.create<ComponentsProvider>(classLoader, componentsClass, apiDefinitionClass)
        deploy(jarPath, apiFactory)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deploy(jarPath: Path, apiFactory: ApiFactory<ComponentsProvider>) {
        // This is required because the default chain doesn't use the AWS_DEFAULT_PROFILE environment variable
        // So if you want to use a non-default profile you have to use a different provider
        val credentialsProvider = if (awsProfile == null) {
            log.info("Using default credentials provider chain")
            DefaultAWSCredentialsProviderChain()
        } else {
            log.info("Using profile credentials provider with profile name '$awsProfile'")
            ProfileCredentialsProvider(awsProfile)
        }
        val roleArn = role ?: createRole(credentialsProvider, region, apiName)
        val functionArn = deployLambda(
            region,
            credentialsProvider,
            apiName,
            roleArn,
            lambdaMemorySize,
            lambdaTimeout,
            jarPath,
            apiFactory.componentsClass,
            apiFactory.apiDefinitionClass,
            environmentVariables ?: mapOf())

        val stageMap = stages.mapValues { (_, stageConfig) -> stageConfig.toStage() }
        val staticFilesBucket = createStaticFilesBucket(apiFactory.api, credentialsProvider)
        val (apiId, stagesNames) = deployApi(
            region,
            credentialsProvider,
            apiName,
            apiFactory.api,
            stageMap,
            functionArn,
            roleArn,
            staticFilesBucket)
        addPermissions(credentialsProvider, apiId, region, functionArn)
        for (stage in stagesNames) {
            log.info("API '$apiName' deployed to https://$apiId.execute-api.$region.amazonaws.com/$stage/")
        }
    }

    private fun createStaticFilesBucket(api: Api<*>, credentialsProvider: AWSCredentialsProvider): String? =
        if (api.staticFiles) {
            val bucket = this.staticFilesBucket ?: createStaticFilesBucket(credentialsProvider, region, apiName)
            val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?:
                builtResourcesDirectory.toPath().resolve("static")
            Files.walk(staticFilesDir, Int.MAX_VALUE)
                .filter { !Files.isDirectory(it) }
                .forEach { uploadFile(credentialsProvider, region, bucket, staticFilesDir, it) }
            bucket
        } else {
            log.debug("API '$apiName' does not serve static files, skipping S3 bucket creation")
            null
        }
}

/** Configuration for an API Gateway stage. */
data class StageConfig(
    var variables: Map<String, String> = mapOf(),
    var deployOnUpdate: Boolean = false,
    var description: String = ""
) {
    fun toStage() = Stage(variables, deployOnUpdate, description)
}
