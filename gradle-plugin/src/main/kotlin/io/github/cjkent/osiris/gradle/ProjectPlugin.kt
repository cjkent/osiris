package io.github.cjkent.osiris.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Plugin to generate an empty Osiris project with build files and example code.
 */
class OsirisProjectPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.create("generateProject", OsirisProjectTask::class.java)
    }
}
/**
 * Task to generate an empty project.
 */
open class OsirisProjectTask : DefaultTask() {

    @TaskAction
    fun generateProject() {
        checkPaths("core", "local-server")
        if (!project.hasProperty("package")) {
            throw IllegalStateException("Please specify the application package using '-Ppackage=...', " +
                "for example: '-Ppackage=com.example.application'")
        }
        val rootPackage = project.property("package") as String
        for (resource in resources) {
            val stream = javaClass.getResourceAsStream(resource)
            val bytes = stream.readBytes()
            val fileStr = String(bytes, Charsets.UTF_8)
            val replacedFile = fileStr
                .replace("\${package}", rootPackage)
                .replace("\${rootArtifactId}", project.name)
                .replace("#set(\$region = '\${AWS::Region}')", "")
                .replace("\${region}", "\${AWS::Region}")
                .replace("\${osirisVersion}", project.properties["osirisVersion"].toString())
                .replace("\${kotlinVersion}", project.properties["kotlinVersion"].toString())
            val projectDir = project.projectDir.toPath()
            val path = projectDir.resolve(insertPackageDirs(resource, rootPackage))
            Files.createDirectories(path.parent)
            logger.info("Writing file {} to {}", path.fileName, path.toAbsolutePath())
            Files.write(path, replacedFile.toByteArray(Charsets.UTF_8))
        }
        Files.createDirectories(project.projectDir.toPath().resolve("core/src/main/static"))
    }

    private fun insertPackageDirs(resource: String, rootPackage: String): Path {
        val match = packageRegex.find(resource) ?: return Paths.get(resource.substring("/archetype-resources/".length))
        val packagePath = rootPackage.replace('.', '/')
        val groupValues = match.groupValues
        val path = "${groupValues[1]}/src/main/kotlin/$packagePath/${groupValues[2]}/${groupValues[3]}"
        return Paths.get(path)
    }

    private fun checkPaths(vararg paths: String) {
        for (path in paths) {
            if (Files.exists(Paths.get(path))) {
                throw IllegalStateException("'$path' already exists, cannot create project")
            }
        }
    }

    companion object {
        private val resources: List<String> = listOf(
            // these come from this project
            "/archetype-resources/settings.gradle",
            "/archetype-resources/build.gradle",
            "/archetype-resources/core/build.gradle",
            "/archetype-resources/local-server/build.gradle",
            // these come from the Maven archetype
            "/archetype-resources/core/src/main/resources/log4j2.xml",
            "/archetype-resources/core/src/main/kotlin/core/generated/Generated.kt",
            "/archetype-resources/core/src/main/kotlin/core/ApiDefinition.kt",
            "/archetype-resources/core/src/main/kotlin/core/Config.kt",
            "/archetype-resources/core/src/main/cloudformation/root.template.example",
            "/archetype-resources/local-server/src/main/resources/log4j2.xml",
            "/archetype-resources/local-server/src/main/kotlin/localserver/Main.kt"
        )

        private val packageRegex: Regex = Regex("/archetype-resources/(.+?)/src/main/kotlin/(.+?)/(.*)")
    }
}
