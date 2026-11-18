package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.policy.PolicyReaderSingleAttestation
import com.dynokore.gst.reconciliation.application.workpaper.AttestWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestationWriter
import com.dynokore.gst.reconciliation.application.workpaper.RunPreparers
import com.dynokore.gst.reconciliation.application.workpaper.SingleAttestationPolicy
import com.dynokore.platform.configresolver.RelaxationReader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * INV-AC-24436 through the composed service (TPL-18, P109): the production configuration starts with
 * exactly one bean of each attestation type, and the BOM pin carries the platform `RelaxationReader`
 * (config-resolver 6.1.0, platform-libs INV-T-24432, first in BOM 0.220.0). No database connection is
 * opened at start.
 */
class AttestationWiringTest {
    private fun root(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("gradle/libs.versions.toml"))) dir = dir.parent
        return requireNotNull(dir) { "gradle/libs.versions.toml not found above the working directory" }
    }

    private fun version(text: String): List<Int> = text.split('.').map { it.toInt() }

    private fun atLeast(
        actual: String,
        minimum: String,
    ): Boolean {
        val a = version(actual)
        val m = version(minimum)
        for (i in 0 until maxOf(a.size, m.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = m.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    @Test
    fun `INV-AC-24436 the context starts with exactly one bean of each attestation type`(
        @TempDir dir: Path,
    ) {
        ReconciliationContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(RunPreparers::class.java).size shouldBe 1
            context.getBeansOfType(SingleAttestationPolicy::class.java).size shouldBe 1
            context.getBeansOfType(RunAttestationWriter::class.java).size shouldBe 1
            context.getBeansOfType(AttestWorkPaper::class.java).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-24436 the single-attestation policy bean is the adapter over the platform readers`(
        @TempDir dir: Path,
    ) {
        ReconciliationContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(RelaxationReader::class.java).size shouldBe 1
            context.getBean(SingleAttestationPolicy::class.java).javaClass shouldBe
                PolicyReaderSingleAttestation::class.java
        }
    }

    @Test
    fun `INV-AC-24436 the BOM pin and the resolved config-resolver carry RelaxationReader`() {
        val repo = root()
        val pin =
            Files
                .readAllLines(repo.resolve("gradle/libs.versions.toml"))
                .single { it.startsWith("platformLibsBom") }
                .substringAfter('"')
                .substringBefore('"')
        atLeast(pin, "0.220.0") shouldBe true
        val resolver =
            Files
                .readAllLines(repo.resolve("reconciliation-adapters/gradle.lockfile"))
                .single { it.startsWith("com.dynokore.platform:config-resolver:") }
                .substringAfterLast(':')
                .substringBefore('=')
        atLeast(resolver, "6.1.0") shouldBe true
    }
}
