package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.gst.evidence.domain.integrity.IntegrityStatus
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class IntegrityStatusQueryTest {
    private val tenant = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val digest = "sha256:" + "a".repeat(64)

    private class Runs(
        private val rows: List<IntegrityRunRow>,
    ) : IntegrityStore {
        override fun lock(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ) = Unit

        override fun failureExists(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ) = false

        override fun insertFailure(
            tenant: TenantId,
            row: IntegrityFailureRow,
        ) = Unit

        override fun insertRun(
            tenant: TenantId,
            row: IntegrityRunRow,
        ) = Unit

        override fun latestRun(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ) = rows.filter { it.entityRef == entityRef && it.period == period }.maxByOrNull { it.runAt }
    }

    private fun run(
        at: String,
        result: RunResult,
        cause: RunCause = RunCause.VERIFICATION,
        length: Long = 8,
        digest: String? = this.digest,
    ) = IntegrityRunRow("E", "2026-Q1", at, Instant.parse(at), result, cause, length, digest)

    private fun status(vararg rows: IntegrityRunRow) =
        IntegrityStatusQueryHandler(Runs(rows.toList())).handle(tenant, IntegrityStatusQuery("E", "2026-Q1"))

    // INV-AC-12970 (its key joins ac-keys.tsv with INV-T-7261, which owns that file)
    @Test
    fun `a VERIFIED run followed by a later FAILED run returns FAILED with the later run's facts`() {
        val later = "2026-05-02T01:00:00Z"
        val laterDigest = "sha256:" + "b".repeat(64)
        status(
            run("2026-05-01T01:00:00Z", RunResult.VERIFIED),
            run(later, RunResult.FAILED, length = 9, digest = laterDigest),
        ) shouldBe IntegrityStatus(IntegrityState.FAILED, Instant.parse(later), 9, laterDigest)
    }

    @Test
    fun `only a VERIFIED run returns VERIFIED with length 8 and the digest`() {
        val at = "2026-05-01T01:00:00Z"
        status(run(at, RunResult.VERIFIED)) shouldBe
            IntegrityStatus(IntegrityState.VERIFIED, Instant.parse(at), 8, digest)
    }

    @Test
    fun `no run returns NEVER_RUN with length 0 and no digest`() {
        val s = status()
        s.result shouldBe IntegrityState.NEVER_RUN
        s.chainLength shouldBe 0
        s.periodDigest shouldBe null
        s.lastRunAt shouldBe null
    }

    @Test
    fun `a FAILED run with cause UNREADABLE returns FAILED`() {
        status(run("2026-05-01T01:00:00Z", RunResult.FAILED, RunCause.UNREADABLE, 0, null)).result shouldBe
            IntegrityState.FAILED
    }
}
