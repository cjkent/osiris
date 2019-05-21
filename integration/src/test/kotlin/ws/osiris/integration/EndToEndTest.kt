package ws.osiris.integration

import com.amazonaws.services.apigateway.model.GetRestApisRequest
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.waiters.WaiterParameters
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import ws.osiris.awsdeploy.AwsProfile
import ws.osiris.awsdeploy.codeBucketName
import ws.osiris.awsdeploy.staticFilesBucketName
import ws.osiris.core.HttpHeaders
import ws.osiris.core.MimeTypes
import ws.osiris.core.TestClient
import ws.osiris.server.HttpTestClient
import ws.osiris.server.Protocol
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertEquals

private val log = LoggerFactory.getLogger("ws.osiris.integration")

private const val TEST_API_NAME = "osiris-e2e-test"
private const val TEST_API_GROUP_ID = "com.example.osiris"
private const val TEST_REGION = "eu-west-1"

fun main(args: Array<String>) {
    if (args.isEmpty()) throw IllegalArgumentException("Must provide the Osiris version as the only argument")
    EndToEndTest.run(args[0])
}

class EndToEndTest private constructor(
    private val region: String,
    private val groupId: String,
    private val apiName: String,
    private val osirisVersion: String
) {

    private val profile: AwsProfile = AwsProfile.default()

    constructor(osirisVersion: String) : this(TEST_REGION, TEST_API_GROUP_ID, TEST_API_NAME, osirisVersion)

    companion object {

        private val log = LoggerFactory.getLogger(EndToEndTest::class.java)

        /**
         * Runs an end-to-end test that
         */
        fun run(osirisVersion: String) {
            EndToEndTest(osirisVersion).run()
        }
    }

    //--------------------------------------------------------------------------------------------------

    private fun run() {
        deleteS3Buckets()
        deleteStack()
        TmpDirResource().use { tmpDirResource ->
            val parentDir = tmpDirResource.path
            val projectDir = createProject(parentDir)
            deployProject(projectDir).use { stackResource ->
                try {
                    val server = "${stackResource.apiId}.execute-api.$region.amazonaws.com"
                    listOf("dev", "prod").forEach { stage ->
                        log.info("Testing API stage $stage")
                        val testClient = HttpTestClient(Protocol.HTTPS, server, basePath = "/$stage")
                        testApi1(testClient)
                    }
                    copyUpdatedFiles(projectDir)
                    deployProject(projectDir)
                    log.info("Testing API stage dev")
                    val devTestClient = HttpTestClient(Protocol.HTTPS, server, basePath = "/dev")
                    testApi2(devTestClient)
                    log.info("Testing API stage prod")
                    val testClient = HttpTestClient(Protocol.HTTPS, server, basePath = "/prod")
                    testApi1(testClient)
                } finally {
                    // the bucket must be empty or the stack can't be deleted
                    emptyBucket(staticFilesBucketName(apiName, null, null), profile.s3Client)
                }
            }
        }
    }

    private fun deleteS3Buckets() {
        val codeBucketName = codeBucketName(apiName, null, null)
        val staticFilesBucketName = staticFilesBucketName(apiName, null, null)
        if (profile.s3Client.doesBucketExistV2(codeBucketName)) deleteBucket(codeBucketName, profile.s3Client)
        log.info("Deleted code bucket {}", codeBucketName)
        if (profile.s3Client.doesBucketExistV2(staticFilesBucketName)) deleteBucket(staticFilesBucketName, profile.s3Client)
        log.info("Deleted static files bucket {}", staticFilesBucketName)
    }

    private fun deleteStack() {
        val stacks = profile.cloudFormationClient.listStacks().stackSummaries.filter { it.stackName == apiName }
        if (stacks.isEmpty()) {
            log.info("No existing stack found named {}, skipping deletion", apiName)
        } else {
            val name = stacks[0].stackName
            log.info("Deleting stack '{}'", name)
            val deleteWaiter = profile.cloudFormationClient.waiters().stackDeleteComplete()
            profile.cloudFormationClient.deleteStack(DeleteStackRequest().apply { stackName = name })
            deleteWaiter.run(WaiterParameters(DescribeStacksRequest().apply { stackName = name }))
            log.info("Deleted stack '{}'", name)
        }
    }

    /**
     * Creates the project by invoking Maven using the Osiris archetype.
     *
     * Returns the project directory.
     */
    private fun createProject(parentDir: Path): Path {
        log.info(
            "Creating project in {} using Maven archetype version {}, groupId={}, artifactId={}",
            parentDir.normalize().toAbsolutePath(),
            osirisVersion,
            groupId,
            apiName)
        val exitValue = ProcessBuilder(
            "mvn",
            "archetype:generate",
            "-DarchetypeGroupId=ws.osiris",
            "-DarchetypeArtifactId=osiris-archetype",
            "-DarchetypeVersion=$osirisVersion",
            "-DgroupId=$groupId",
            "-DartifactId=$apiName",
            "-DinteractiveMode=false")
            .directory(parentDir.toFile())
            .inheritIO()
            .start()
            .waitFor()
        if (exitValue == 0) {
            log.info("Project created successfully")
        } else {
            throw IllegalStateException("Project creation failed, Maven exit value = $exitValue")
        }
        return parentDir.resolve(apiName)
    }

    /**
     * Deploys the project by invoking `mvn deploy` and returns the ID of the API.
     */
    private fun deployProject(projectDir: Path): StackResource {
        log.info("Deploying project from directory $projectDir")
        val exitValue = ProcessBuilder("mvn", "deploy")
            .directory(projectDir.toFile())
            .inheritIO()
            .start()
            .waitFor()
        if (exitValue == 0) {
            log.info("Project deployed successfully")
        } else {
            throw IllegalStateException("Project deployment failed, Maven exit value = $exitValue")
        }
        val apiId = profile.apiGatewayClient.getRestApis(GetRestApisRequest()).items.firstOrNull { it.name == apiName }?.id ?:
            throw IllegalStateException("No REST API found named $apiName")
        log.info("ID of the deployed API: {}", apiId)
        return StackResource(apiId)
    }

    private fun testApi1(client: TestClient) {
        val objectMapper = jacksonObjectMapper()
        fun Any?.parseJson(): Map<*, *> {
            val json = this as? String ?: throw IllegalArgumentException("Value is not a string: $this")
            return objectMapper.readValue(json, Map::class.java)
        }

        val response1 = client.get("/helloworld")
        assertEquals(mapOf("message" to "hello, world!"), response1.body.parseJson())
        assertEquals(MimeTypes.APPLICATION_JSON, response1.headers[HttpHeaders.CONTENT_TYPE])
        assertEquals(200, response1.status)

        val response2 = client.get("/helloplain")
        assertEquals("hello, world!", response2.body)
        assertEquals(MimeTypes.TEXT_PLAIN, response2.headers[HttpHeaders.CONTENT_TYPE])

        assertEquals(mapOf("message" to "hello, Alice!"), client.get("/helloqueryparam?name=Alice").body.parseJson())
        assertEquals(mapOf("message" to "hello, Peter!"), client.get("/hello/Peter").body.parseJson())
        assertEquals(mapOf("message" to "hello, Bob!"), client.get("/helloenv").body.parseJson())

        log.info("API tested successfully")
    }

    private fun testApi2(client: TestClient) {
        val maxTries = 5

        fun testApi2(tries: Int, sleepMs: Long) {
            try {
                assertApi(client)
            } catch (e: Throwable) {
                if (tries < maxTries) {
                    log.info("assertApi failed, waiting for {}ms and retrying", sleepMs)
                    Thread.sleep(sleepMs)
                    testApi2(tries + 1, sleepMs * 2)
                } else {
                    log.info("assertApi didn't pass after {} attempts", maxTries)
                    throw e
                }
            }
        }
        testApi2(1, 1000)
    }

    private fun copyUpdatedFiles(projectDir: Path) {
        val e2eFilesDir = Paths.get("integration/src/test/e2e-test")
        val projectStaticDir = projectDir.resolve("core/src/main/static")
        val indexSrc = e2eFilesDir.resolve("index.html")
        val indexDest = projectStaticDir.resolve(indexSrc.fileName)
        Files.copy(indexSrc, indexDest)
        log.info("Copied {} to {}", indexSrc.toAbsolutePath(), indexDest.toAbsolutePath())
        val bazDir = projectStaticDir.resolve("baz")
        Files.createDirectories(bazDir)
        log.info("Created directory {}", bazDir.toAbsolutePath())
        val barSrc = e2eFilesDir.resolve("bar.html")
        val barDest = bazDir.resolve(barSrc.fileName)
        Files.copy(barSrc, barDest)
        log.info("Copied {} to {}", barSrc.toAbsolutePath(), barDest.toAbsolutePath())
        val srcDir = projectDir.resolve("core/src/main/kotlin/com/example/osiris/core")
        val apiSrc = e2eFilesDir.resolve("ApiDefinition.kt")
        val apiDest = srcDir.resolve(apiSrc.fileName)
        Files.copy(apiSrc, apiDest, StandardCopyOption.REPLACE_EXISTING)
        log.info("Copied {} to {}", apiSrc.toAbsolutePath(), apiDest.toAbsolutePath())
        val configSrc = e2eFilesDir.resolve("Config.kt")
        val configDest = srcDir.resolve(configSrc.fileName)
        Files.copy(configSrc, configDest, StandardCopyOption.REPLACE_EXISTING)
        log.info("Copied {} to {}", configSrc.toAbsolutePath(), configDest.toAbsolutePath())
    }

    /**
     * Auto-closable resource representing a CloudFormation stack; allows the stack to be automatically deleted
     * when testing is complete.
     */
    inner class StackResource(val apiId: String) : AutoCloseable {

        override fun close() {
            deleteStack()
        }
    }
}

/**
 * Auto-closable wrapper around a temporary directory.
 *
 * When it is closed the directory and its contents are deleted.
 */
private class TmpDirResource : AutoCloseable {

    internal val path: Path = Files.createTempDirectory("osiris-e2e-test")

    override fun close() {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, basicFileAttributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, ioException: IOException?): FileVisitResult {
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

// --------------------------------------------------------------------------------------------------

/**
 * Deletes an S3 bucket, deleting its contents first if it is not empty.
 */
fun deleteBucket(bucketName: String, s3Client: AmazonS3) {
    emptyBucket(bucketName, s3Client)
    s3Client.deleteBucket(bucketName)
    log.info("Deleted bucket {}", bucketName)
}

/**
 * Deletes all objects from an S3 bucket.
 */
private tailrec fun emptyBucket(bucketName: String, s3Client: AmazonS3) {
    if (!s3Client.doesBucketExistV2(bucketName)) return
    val objects = s3Client.listObjects(bucketName).objectSummaries
    if (objects.isEmpty()) {
        return
    } else {
        objects.forEach { s3Client.deleteObject(bucketName, it.key) }
        emptyBucket(bucketName, s3Client)
    }
}
