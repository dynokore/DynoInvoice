package com.dynokore.platform.serviceruntime.datasource

import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** INV-T-3913 (STK-29, ruling INV-Q-3909): the provider reads the mounted password on every call and never shows it. */
class SecretStoreCredentialsProviderTest {
    @Test
    fun `every call reads the password mounted at that moment`(
        @TempDir mountRoot: Path,
    ) {
        val file = mountRoot.resolve("db-app")
        Files.writeString(file, "first-value\n")
        val provider =
            SecretStoreCredentialsProvider(MountedFileSecretStore(mountRoot), "submission_app", SecretRef("db-app"))

        provider.credentials.username shouldBe "submission_app"
        provider.credentials.password shouldBe "first-value"
        Files.writeString(file, "rotated-value")
        provider.credentials.password shouldBe "rotated-value"
        Files.delete(file)
        shouldThrow<SecretNotFound> { provider.credentials }.message shouldBe "no secret bound at 'db-app'"
    }

    @Test
    fun `toString names the role and the reference only (CS-24)`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("db-app"), "never-shown")
        val provider =
            SecretStoreCredentialsProvider(MountedFileSecretStore(mountRoot), "submission_app", SecretRef("db-app"))
        provider.credentials
        provider.toString() shouldBe "SecretStoreCredentialsProvider(username=submission_app, passwordRef=db-app)"
    }

    @Test
    fun `a blank username is refused`(
        @TempDir mountRoot: Path,
    ) {
        shouldThrow<IllegalArgumentException> {
            SecretStoreCredentialsProvider(MountedFileSecretStore(mountRoot), " ", SecretRef("db-app"))
        }
    }
}
