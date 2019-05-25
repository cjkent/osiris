package ws.osiris.integration

import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

@Test
class TestHelpersTest {

    fun copyDirectoryContents() {
        TmpDirResource().use {
            copyDirectoryContents(Paths.get("src/test/static"), it.path)
            assertTrue(Files.isRegularFile(it.path.resolve("baz/bar.html")))
            assertTrue(Files.isRegularFile(it.path.resolve("index.html")))
        }
    }
}
