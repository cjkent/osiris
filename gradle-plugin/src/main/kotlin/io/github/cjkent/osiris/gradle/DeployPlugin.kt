package io.github.cjkent.osiris.gradle

import io.github.cjkent.osiris.awsdeploy.DeployableProject
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.api.tasks.bundling.Jar
import java.nio.file.Path
import kotlin.reflect.KClass

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
        val fatJarTask = project.tasks.create("fatJar", Jar::class.java)
        fatJarTask.baseName = project.name + "-jar-with-dependencies"
        val deployTask = createTask(project, deployableProject, extension, "deploy", OsirisDeployTask::class)
        val generateTemplateTask = createTask(
            project,
            deployableProject,
            extension,
            "generateCloudFormation",
            OsirisGenerateCloudFormationTask::class
        )
        project.afterEvaluate {
            for (task in project.getTasksByName("assemble", false)) fatJarTask.dependsOn(task)
            generateTemplateTask.dependsOn(fatJarTask)
            deployTask.dependsOn(generateTemplateTask)
            val jarPaths = project.configurations.getByName("compile").map {
                @Suppress("IMPLICIT_CAST_TO_ANY")
                if (it.isDirectory) it else project.zipTree(it)
            }
            fatJarTask.from(jarPaths)
            val jarTasks = project.getTasksByName("jar", false)
            for (jarTask in jarTasks) fatJarTask.with(jarTask as CopySpec)
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
 *         accountName = "dev"
 *     }
 */
open class OsirisDeployPluginExtension(
    var rootPackage: String? = null,
    var staticFilesDirectory: String? = null,
    var accountName: String? = null
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
    project: Project,
    private val extension: OsirisDeployPluginExtension
) : DeployableProject {

    override val sourceDir: Path = project.projectDir.toPath().resolve("src/main")
    override val name: String = project.name
    override val buildDir: Path = project.buildDir.toPath()
    override val jarBuildDir: Path = buildDir.resolve("libs")
    override val rootPackage: String get() = extension.rootPackage ?: throw IllegalStateException("rootPackage required")
    // TODO should this check for the default version. "undefined"?
    override val version: String? = project.version.toString()
    override val accountName: String? get() = extension.accountName
    override val staticFilesDirectory: String? get() = extension.staticFilesDirectory
}
