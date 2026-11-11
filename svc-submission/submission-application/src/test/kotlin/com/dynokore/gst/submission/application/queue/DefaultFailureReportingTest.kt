package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class DefaultFailureReportingTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = "doc-1"
    private val entries =
        listOf(
            CatalogueEntry("SUB-003", "Platform", "MANUAL"),
            CatalogueEntry("SUB-001", "Platform", "AUTO_RETRY"),
            CatalogueEntry("SUB-900", "Platform", null),
        ).associateBy { it.code }

    private class Recorded(
        val tenant: TenantId,
        val doc: String,
        val version: Int,
        val attempt: Int,
        val code: String,
    )

    private val recorded = mutableListOf<Recorded>()
    private val staged = mutableListOf<SubmissionFailure>()
    private val codes =
        object : FailureCodes {
            override fun record(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                attemptNumber: Int,
                code: String,
            ) {
                recorded += Recorded(tenant, docUuid, version, attemptNumber, code)
            }

            override fun lastOf(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): String? = if (version == 3) "SUB-001" else null
        }
    private val reporting = DefaultFailureReporting({ entries[it] }, codes, { staged += it })

    private fun dispatch(version: Int) =
        Dispatch(doc, version, "E-001", 2, UUID.randomUUID(), null, ClaimedItem(1L, tenant, doc, Lane.LIVE))

    @Test
    fun `INV-AC-20650 V1 REJECTED with SUB-003 stages the catalogue members and MANUAL`() {
        reporting.versionFailed(dispatch(1), LifecycleState.REJECTED, "SUB-003")
        staged shouldBe listOf(SubmissionFailure(doc, 1, "SUB-003", "MANUAL", "SUB-003", "Platform", "MANUAL"))
    }

    @Test
    fun `INV-AC-20650 V2 REJECTED with an unknown code stages TERMINAL and no catalogue members`() {
        reporting.versionFailed(dispatch(2), LifecycleState.REJECTED, "X-999")
        staged shouldBe listOf(SubmissionFailure(doc, 2, "X-999", "TERMINAL", null, null, null))
    }

    @Test
    fun `INV-AC-20650 V5 REJECTED with SUB-900 stages TERMINAL and no disposition member`() {
        reporting.versionFailed(dispatch(5), LifecycleState.REJECTED, "SUB-900")
        staged shouldBe listOf(SubmissionFailure(doc, 5, "SUB-900", "TERMINAL", "SUB-900", "Platform", null))
    }

    @Test
    fun `INV-AC-20650 V3 DEAD_LETTERED uses the persisted code and is MANUAL not AUTO_RETRY`() {
        reporting.versionFailed(dispatch(3), LifecycleState.DEAD_LETTERED, null)
        staged shouldBe listOf(SubmissionFailure(doc, 3, "SUB-001", "MANUAL", "SUB-001", "Platform", "AUTO_RETRY"))
    }

    @Test
    fun `INV-AC-20650 V4 DEAD_LETTERED without a persisted code throws and stages nothing`() {
        shouldThrow<IllegalStateException> { reporting.versionFailed(dispatch(4), LifecycleState.DEAD_LETTERED, null) }
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20650 an ACKNOWLEDGED version throws IllegalArgumentException and stages nothing`() {
        shouldThrow<IllegalArgumentException> {
            reporting.versionFailed(dispatch(6), LifecycleState.ACKNOWLEDGED, "SUB-003")
        }
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20650 recording hands the code store tenant doc version attempt and code`() {
        reporting.attemptFailed(dispatch(3), "SUB-001")
        recorded.single().let {
            it.tenant shouldBe tenant
            it.doc shouldBe doc
            it.version shouldBe 3
            it.attempt shouldBe 2
            it.code shouldBe "SUB-001"
        }
    }

    @Test
    fun `a REJECTED report without a rejected code stops and stages nothing`() {
        shouldThrow<IllegalArgumentException> { reporting.versionFailed(dispatch(1), LifecycleState.REJECTED, null) }
        staged shouldBe emptyList()
    }
}
