package ws.osiris.maven

import org.apache.maven.artifact.Artifact
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import ws.osiris.aws.validateName
import ws.osiris.awsdeploy.DeployException
import ws.osiris.awsdeploy.DeployableProject
import ws.osiris.core.log
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parent of the Osiris Mojo classes; contains common configuration parameters used by all subclasses.
 */
abstract class OsirisMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project.groupId}")
    lateinit var rootPackage: String

    @Parameter(property = "osiris.environmentName")
    var environmentName: String? = null

    @Parameter(property = "osiris.awsProfile")
    var awsProfile: String? = null

    @Parameter(property = "osiris.stackName")
    var stackName: String? = null

    @Parameter
    var staticFilesDirectory: String? = null

    @Component
    private lateinit var mavenProject: MavenProject

    protected val project: DeployableProject get() =
        MavenDeployableProject(rootPackage, environmentName, staticFilesDirectory, awsProfile, stackName, mavenProject)
}

//--------------------------------------------------------------------------------------------------

/**
 * Mojo defining a goal to generate a CloudFormation template using the API definition and additional configuration.
 *
 * Generating files in the package phase doesn't feel quite right. But the API must be instantiated to build
 * the CloudFormation template. In order to safely instantiate the API we need all the dependencies available.
 * The easiest way to do this is to use the project jar which is only built during packaging.
 */
@Mojo(
    name = "generate-cloudformation",
    // TODO could this be done in COMPILE? would have to build the ApiFactory using project classes instead of the jar
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
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
@Mojo(
    name = "deploy",
    defaultPhase = LifecyclePhase.DEPLOY,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
class DeployMojo : OsirisMojo() {

    override fun execute() {
        try {
            project.deploy()
        } catch (e: DeployException) {
            throw MojoFailureException(e.message, e)
        }
    }
}

@Mojo(
    name = "open",
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
class OpenMojo : OsirisMojo() {

    @Parameter(property = "stage", required = true)
    lateinit var stage: String

    @Parameter(property = "path", required = true)
    lateinit var path: String

    override fun execute() {
        try {
            project.openBrowser(stage, path)
        } catch (e: DeployException) {
            throw MojoFailureException(e.message, e)
        }
    }
}

//--------------------------------------------------------------------------------------------------

class MavenDeployableProject(
    override val rootPackage: String,
    override val environmentName: String?,
    override val staticFilesDirectory: String?,
    override val awsProfile: String?,
    override val stackName: String?,
    private val project: MavenProject
) : DeployableProject {
    override val name: String = project.artifactId
    override val version: String = project.version
    override val buildDir: Path = Paths.get(project.build.directory)
    override val zipBuildDir: Path = buildDir
    override val sourceDir: Path = Paths.get(project.build.sourceDirectory).parent
    override val runtimeClasspath: List<Path> get() = project.artifacts.map { (it as Artifact).file.toPath() }.toList()
    override val projectJar: Path get() {
        return if (project.artifact.file != null) {
            val path = project.artifact.file.toPath()
            log.debug("project.artifact.file != null, exists = {}", Files.exists(path))
            path
        } else {
            // This is a horrible hack. project.artifact.file is null in some Mojos and not in others
            val jarPath = buildDir.resolve(project.artifact.artifactId + "-" + project.artifact.version + ".jar")
            if (Files.exists(jarPath)) {
                log.debug("project.artifact.file == null, path {} exists", jarPath.toAbsolutePath())
                return jarPath
            } else {
                log.debug("project.artifact.file == null, path {} does not exist", jarPath.toAbsolutePath())
                throw FileNotFoundException("Project jar not found, run mvn package")
            }
        }
    }

    init {
        validateName(environmentName)
        validateName(stackName)
    }
}
