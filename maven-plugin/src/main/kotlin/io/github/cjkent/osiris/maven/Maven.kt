package io.github.cjkent.osiris.maven

import io.github.cjkent.osiris.awsdeploy.DeployException
import io.github.cjkent.osiris.awsdeploy.DeployableProject
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parent of the Osiris Mojo classes; contains common configuration parameters used by all subclasses.
 */
abstract class OsirisMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project.groupId}")
    lateinit var rootPackage: String

    @Parameter
    var apiProperty: String? = null

    @Parameter
    var componentsFunction: String? = null

    @Parameter
    var configProperty: String? = null

    @Component
    private lateinit var mavenProject: MavenProject

    protected val project: DeployableProject get() =
        MavenDeployableProject(rootPackage, apiProperty, componentsFunction, configProperty, mavenProject)
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generates a subclass of ProxyLambda that runs the web app in AWS.
 */
@Mojo(name = "generate-lambda", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
class GenerateLambdaMojo : OsirisMojo() {

    override fun execute() {
        try {
            project.generateKotlin("GeneratedLambda", "core")
        } catch (e: DeployException) {
            throw MojoFailureException(e.message, e)
        }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generates a `main` function to run the API in a local Jetty server.
 */
@Mojo(name = "generate-local-server", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
class GenerateLocalServerMojo : OsirisMojo() {

    override fun execute() {
        try {
            project.generateKotlin("GeneratedLocalServer", "localserver")
        } catch (e: DeployException) {
            throw MojoFailureException(e.message, e)
        }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generate a CloudFormation template using the API definition and additional configuration.
 *
 * Generating files in the package phase doesn't feel quite right. But the API must be instantiated to build
 * the CloudFormation template. In order to safely instantiate the API we need all the dependencies available.
 * The easiest way to do this is to use the distribution jar which is only built during packaging.
 */
@Mojo(name = "generate-cloudformation", defaultPhase = LifecyclePhase.PACKAGE)
class GenerateCloudFormationMojo : OsirisMojo() {

    override fun execute() {
        try {
            project.generateCloudFormation()
        } catch (e: DeployException) {
            throw MojoFailureException(e.message, e)
        }
    }
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining the deployment goal; deploys an API and lambda function to AWS.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
class DeployMojo : OsirisMojo() {

    @Parameter
    private var staticFilesDirectory: String? = null

    override fun execute() {
        try {
            project.deploy(staticFilesDirectory)
        } catch (e: DeployException) {
            throw MojoFailureException(e.message, e)
        }
    }
}

//--------------------------------------------------------------------------------------------------

class MavenDeployableProject(
    override val rootPackage: String,
    override val apiProperty: String?,
    override val componentsFunction: String?,
    override val configProperty: String?,
    project: MavenProject
) : DeployableProject {

    override val name: String = project.artifactId
    override val version: String = project.version
    override val buildDir: Path = Paths.get(project.build.directory)
    override val sourceDir: Path = Paths.get(project.build.sourceDirectory).parent
}
