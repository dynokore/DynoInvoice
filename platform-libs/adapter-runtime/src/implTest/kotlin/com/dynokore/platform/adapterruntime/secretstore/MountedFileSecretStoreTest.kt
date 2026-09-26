package com.dynokore.platform.adapterruntime.secretstore

import com.dynokore.platform.adapterruntime.SecretRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** STK-29: "rotation by file watch without restart" — no restart needed here because there is no cache to invalidate. */
class MountedFileSecretStoreTest {
    @Test
    fun `a rotated file is visible on the very next read, with no restart`(
        @TempDir mountRoot: Path,
    ) {
        val ref = SecretRef("rotating-secret")
        val file = mountRoot.resolve(ref.value)
        val store = MountedFileSecretStore(mountRoot)

        Files.writeString(file, "old-value")
        store.read(ref).reveal() shouldBe "old-value"

        Files.writeString(file, "new-value") // simulates ESO / kubelet rotating the mounted file in place
        store.read(ref).reveal() shouldBe "new-value"
    }

    @Test
    fun `a trailing newline from the mounted file is not part of the secret`(
        @TempDir mountRoot: Path,
    ) {
        val ref = SecretRef("newline-secret")
        Files.writeString(mountRoot.resolve(ref.value), "s3cr3t\n")

        MountedFileSecretStore(mountRoot).read(ref).reveal() shouldBe "s3cr3t"
    }
}
