package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** STK-26: rotation by file watch, no restart — the same discipline `MountedFileSecretStore` uses for STK-29. */
class ProjectedServiceAccountTokenSourceTest {
    @Test
    fun `a rotated token file is visible on the very next call, with no restart`(
        @TempDir mountRoot: Path,
    ) {
        val tokenFile = mountRoot.resolve("token")
        val source = ProjectedServiceAccountTokenSource(tokenFile)

        Files.writeString(tokenFile, "old-token\n")
        source.current() shouldBe WorkloadToken("old-token")

        Files.writeString(tokenFile, "new-token\n") // simulates the kubelet's own periodic projection refresh
        source.current() shouldBe WorkloadToken("new-token")
    }

    @Test
    fun `no projected token file is an infrastructure failure, not a refused call`(
        @TempDir mountRoot: Path,
    ) {
        shouldThrow<WorkloadTokenUnavailable> { ProjectedServiceAccountTokenSource(mountRoot.resolve("missing")).current() }
    }
}
