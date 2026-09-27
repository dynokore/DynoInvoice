package com.dynokore.platform.serviceruntime.binding

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

    @Test
    fun `a service reads only its own mount root, never a sibling one`(
        @TempDir root: Path,
    ) {
        val ownMount = Files.createDirectory(root.resolve("ingestion-svc"))
        val otherMount = Files.createDirectory(root.resolve("masterdata-svc"))
        Files.writeString(otherMount.resolve("db-password"), "other-services-password")

        val store = MountedFileSecretStore(ownMount)

        // The other service's secret is not addressable from this store at all: the
        // ref would have to name a path outside ownMount, which SecretRef itself
        // refuses (SecretRefTest), and even a raw resolve stays confined to ownMount.
        val thrown =
            io.kotest.assertions.throwables.shouldThrow<SecretNotFound> {
                store.read(SecretRef("db-password"))
            }
        thrown.ref shouldBe SecretRef("db-password")
    }
}
