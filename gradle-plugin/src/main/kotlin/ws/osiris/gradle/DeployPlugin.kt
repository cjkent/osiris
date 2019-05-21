package ws.osiris.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.api.tasks.bundling.Zip
import ws.osiris.aws.validateName
import ws.osiris.awsdeploy.DeployableProject
import java.nio.file.Path
import kotlin.reflect.KClass

/** The version Gradle gives to a project if no version is specified. */
private const val NO_VERSION: String = "unspecified"

/**
 * Gradle plugin that handles building and deploying Osiris projects.
 *
 * Adds tasks to:
 *   * Build a jar containing the application and all its dependencies
 *   * Generate a CloudFormation template that defines the API and lambda
 *   * Deploy the application to AWS
 */
class OsirisDeployPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("osiris", OsirisDeployPluginExtension::class.java)
        val deployableProject = GradleDeployableProject(project, extension)
        val zipTask = project.tasks.create("zip", Zip::class.java)
        val deployTask = createTask(project, deployableProject, extension, "deploy", OsirisDeployTask::class)
        val generateTemplateTask = createTask(
            project,
            deployableProject,
            extension,
            "generateCloudFormation",
            OsirisGenerateCloudFormationTask::class
        )
        project.afterEvaluate {
            for (task in project.getTasksByName("assemble", false)) zipTask.dependsOn(task)
            generateTemplateTask.dependsOn(zipTask)
            deployTask.dependsOn(generateTemplateTask)
            val jarPaths = mutableListOf<Path>()
            jarPaths.addAll(deployableProject.runtimeClasspath)
            jarPaths.add(deployableProject.projectJar)
            zipTask.from(jarPaths)
            zipTask.into("lib")
            val versionStr = if (project.version == NO_VERSION) "" else "-${project.version}"
            zipTask.archiveName = "${project.name}$versionStr-dist.zip"
        }
    }

    private fun <T : OsirisTask> createTask(
        project: Project,
        deployableProject: DeployableProject,
        extension: OsirisDeployPluginExtension,
        name: String,
        type: KClass<T>
    ): T = project.tasks.create(name, type.java).apply {
        this.deployableProject = deployableProject
        this.extension = extension
    }
}

/**
 * Allows the plugins to be configured using the Gradle DSL.
 *
 *     osiris {
 *         rootPackage = "com.example.application"
 *         staticFilesDirectory = "/some/directory"
 *         environmentName = "dev"
 *         awsProfile =  "dev-account"
 *     }
 */
open class OsirisDeployPluginExtension(
    var rootPackage: String? = null,
    var staticFilesDirectory: String? = null,
    var environmentName: String? = null,
    var awsProfile: String? = null,
    var stackName: String? = null
)

/**
 * Base class for tasks.
 */
abstract class OsirisTask : DefaultTask() {

    internal lateinit var deployableProject: DeployableProject
    internal lateinit var extension: OsirisDeployPluginExtension
}

/**
 * Task to generate the CloudFormation template.
 */
open class OsirisGenerateCloudFormationTask : OsirisTask() {

    @TaskAction
    fun generate() {
        try {
            deployableProject.generateCloudFormation()
        } catch (e: Exception) {
            throw TaskExecutionException(this, e)
        }
    }
}


/**
 * Task to upload the static files to S3, the jar to the S3 code bucket and then deploy the CloudFormation stack.
 */
open class OsirisDeployTask : OsirisTask() {

    @TaskAction
    fun deploy() {
        try {
            val stageUrls = deployableProject.deploy()
            for ((stage, url) in stageUrls) {
                logger.lifecycle("Deployed to stage '$stage' at $url")
            }
        } catch (e: Exception) {
            throw TaskExecutionException(this, e)
        }
    }
}

/**
 * Integrates with the deployment logic in the AWS deployment module.
 */
private class GradleDeployableProject(
    private val project: Project,
    private val extension: OsirisDeployPluginExtension
) : DeployableProject {

    override val sourceDir: Path = project.projectDir.toPath().resolve("src/main")
    override val name: String = project.name
    override val buildDir: Path = project.buildDir.toPath()
    override val zipBuildDir: Path = buildDir.resolve("distributions")
    override val rootPackage: String get() = extension.rootPackage ?: throw IllegalStateException("rootPackage required")
    override val version: String? = if (project.version == NO_VERSION) null else project.version.toString()
    override val environmentName: String? get() = extension.environmentName
    override val staticFilesDirectory: String? get() = extension.staticFilesDirectory
    override val awsProfile: String? get() = validateName(extension.awsProfile)
    override val stackName: String? get() = validateName(extension.stackName)
    override val projectJar: Path
        get() = project.configurations.getByName("runtime").allArtifacts.files.singleFile.toPath()
    override val runtimeClasspath: List<Path>
        get() = project.configurations.getByName("runtime").resolvedConfiguration.resolvedArtifacts.map { it.file.toPath() }
}
