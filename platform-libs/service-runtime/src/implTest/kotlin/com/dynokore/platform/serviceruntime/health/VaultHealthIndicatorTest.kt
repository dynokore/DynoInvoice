package com.dynokore.platform.serviceruntime.health

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.health.contributor.Status
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR-AH.17: the vault is one of the three things every cold start needs; §36.7
 * "a cold start while the vault is down fails visibly" — `DOWN`, not `DEGRADED`
 * (a missing vault is not a domain dependency).
 */
class VaultHealthIndicatorTest {
    @Test
    fun `reports UP when the mount root exists and is readable`(
        @TempDir mountRoot: Path,
    ) {
        VaultHealthIndicator(mountRoot).health().status shouldBe Status.UP
    }

    @Test
    fun `reports DOWN, not DEGRADED, when the vault mount is missing`(
        @TempDir root: Path,
    ) {
        val neverMounted = root.resolve("not-mounted")
        VaultHealthIndicator(neverMounted).health().status shouldBe Status.DOWN
    }

    @Test
    fun `reports DOWN when the mount root is a file, not a directory`(
        @TempDir root: Path,
    ) {
        val notADirectory = root.resolve("vault-is-a-file")
        Files.writeString(notADirectory, "oops")

        VaultHealthIndicator(notADirectory).health().status shouldBe Status.DOWN
    }
}
