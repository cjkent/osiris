package ws.osiris.aws

import org.testng.Assert.assertThrows
import org.testng.annotations.Test

@Test
class ConfigTest {

    fun validateBucketNameValidName() {
        validateBucketName("foo-bar-baz")
    }

    fun validateBucketNameTooLong() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBucketName("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz")
        }
    }

    fun validateBucketNameTooShort() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBucketName("ab")
        }
    }

    fun validateBucketNameUpperCase() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBucketName("Foo-bar-baz")
        }
    }

    fun validateBucketNameIllegalChars() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBucketName("foo-bar-!baz")
        }
    }

    fun validateBucketNameStartsOrEndsWithDash() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBucketName("-foo-bar-baz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBucketName("foo-bar-baz-")
        }
    }

    fun configInvalidStaticFilesBucket() {
        assertThrows(IllegalArgumentException::class.java) {
            ApplicationConfig(
                staticFilesBucket = "UpperCaseName",
                applicationName = "notUsed",
                bucketSuffix = "foo",
                stages = listOf(
                    Stage(
                        name = "test",
                        deployOnUpdate = false
                    )
                )
            )
        }
    }

    fun configInvalidAppName() {
        assertThrows(IllegalArgumentException::class.java) {
            ApplicationConfig(
                applicationName = "not valid",
                bucketSuffix = "foo",
                stages = listOf(
                    Stage(
                        name = "test",
                        deployOnUpdate = false
                    )
                )
            )
        }
    }

    fun configInvalidStaticCodeBucket() {
        assertThrows(IllegalArgumentException::class.java) {
            ApplicationConfig(
                codeBucket = "UpperCaseName",
                applicationName = "notUsed",
                bucketSuffix = "foo",
                stages = listOf(
                    Stage(
                        name = "test",
                        deployOnUpdate = false
                    )
                )
            )
        }
    }

    fun validateNameValidName() {
        validateName("Foo-Bar")
    }

    fun validateNameIllegalChars() {
        assertThrows(IllegalArgumentException::class.java) {
            validateName("Foo_Bar")
        }
    }
}
