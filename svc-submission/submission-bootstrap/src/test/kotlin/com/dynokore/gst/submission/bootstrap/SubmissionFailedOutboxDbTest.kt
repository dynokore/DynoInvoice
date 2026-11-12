package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.application.queue.LockedSubmission
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** `SubmissionFailed` through the real catalogue, code store and outbox, in the outcome transaction (INV-AC-20656). */
class SubmissionFailedOutboxDbTest {
    private val mapper = JsonMapper.builder().build()
    private val type = "com.dynokore.gst.submission.SubmissionFailed.v1"
    private val topic = "gst.submission.submission-failed.v1"

    private fun run(
        dir: Path,
        block: (OutcomeFixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> block(OutcomeFixture(context)) }
    }

    private fun rejection(code: String) =
        TransportResult.Rejected(
            listOf(RejectionReason(code, null, "bad"), RejectionReason("ZZZ-999", null, "x")),
            "r".toByteArray(),
        )

    private fun OutcomeFixture.sent(
        tenant: UUID,
        doc: String,
    ) = (worker.takeNext() as SentResult.Sent).dispatch.also { check(tenant == tenant && doc == doc) }

    private fun OutcomeFixture.events(
        tenant: UUID,
        doc: String,
    ) = asTenant(tenant) {
        dsl
            .selectFrom(Outbox.OUTBOX)
            .where(Outbox.OUTBOX.DOC_UUID.eq(UUID.fromString(doc)))
            .and(Outbox.OUTBOX.TYPE.eq(type))
            .fetch()
            .toList()
    }

    private fun OutcomeFixture.classes(
        tenant: UUID,
        doc: String,
    ) = attempts(tenant, doc).map { it.failureClass }

    private fun payload(
        f: OutcomeFixture,
        tenant: UUID,
        doc: String,
    ): Map<String, Any?> {
        val rows = f.events(tenant, doc)
        rows.size shouldBe 1
        rows[0].type shouldBe type
        rows[0].topic shouldBe topic
        rows[0].subject shouldBe doc
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(checkNotNull(rows[0].data).data(), Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `INV-AC-20656 V1 a rejection with SUB-003 stores the code and stages the catalogue members`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val dispatch = f.sent(tenant, doc)

        f.applier().apply(dispatch, rejection(ErrorCode.SUB_003.code))

        f.stateOf(tenant, doc, 1) shouldBe "REJECTED"
        f.classes(tenant, doc) shouldBe listOf(ErrorCode.SUB_003.code)
        payload(f, tenant, doc) shouldBe
            mapOf(
                "docUuid" to doc,
                "version" to 1,
                "failureClass" to ErrorCode.SUB_003.code,
                "retryDisposition" to ErrorCode.SUB_003.disposition?.name,
                "code" to ErrorCode.SUB_003.code,
                "remediationOwner" to ErrorCode.SUB_003.owner,
                "disposition" to ErrorCode.SUB_003.disposition?.name,
            )
        ErrorCode.SUB_003.disposition?.name shouldBe "MANUAL"
        ErrorCode.SUB_003.owner shouldBe "Platform"
    }

    @Test
    fun `INV-AC-20656 V2 a rejection with X-999 is TERMINAL with no catalogue member`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)

        f.applier().apply(f.sent(tenant, doc), rejection("X-999"))

        f.classes(tenant, doc) shouldBe listOf("X-999")
        payload(f, tenant, doc) shouldBe
            mapOf("docUuid" to doc, "version" to 1, "failureClass" to "X-999", "retryDisposition" to "TERMINAL")
    }

    @Test
    fun `INV-AC-20656 V3 a dead letter reports the code of the last attempt and MANUAL`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val cycle =
            f.cycle(
                FakeTransport(
                    TransportResult.TransientFailure("SUB-001", "t".toByteArray()),
                    TransportResult.TransientFailure("X-998", "t".toByteArray()),
                ),
            )
        cycle.runOnce() shouldBe true
        f.events(tenant, doc) shouldBe emptyList()
        f.stateOf(tenant, doc, 1) shouldBe "RETRY"
        f.clock.now = f.clock.now.plus(Duration.ofHours(1))
        f.dueRunner { listOf(TenantId(tenant)) }.runOnce(f.clock.now)
        f.boundary = null

        cycle.runOnce() shouldBe true

        f.stateOf(tenant, doc, 1) shouldBe "DEAD_LETTERED"
        f.classes(tenant, doc) shouldBe listOf("SUB-001", "X-998")
        payload(f, tenant, doc) shouldBe
            mapOf("docUuid" to doc, "version" to 1, "failureClass" to "X-998", "retryDisposition" to "MANUAL")
    }

    @Test
    fun `INV-AC-20656 V4 an accepted version has no failure class and no event`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)

        f.applier().apply(
            f.sent(tenant, doc),
            TransportResult.Accepted("AP-1", f.clock.now, "ok".toByteArray()),
        )

        f.classes(tenant, doc) shouldBe listOf(null)
        f.events(tenant, doc) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20656 V5 a write after the event fails and the rollback leaves no class and no event`(
        @TempDir dir: Path,
    ) = run(dir) { f ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        f.queued(tenant, doc)
        val dispatch = f.sent(tenant, doc)
        // the last read of the outcome transaction comes after the event is staged
        var locks = 0
        val failing =
            object : SubmissionStore by f.store {
                override fun lockForUpdate(
                    tenant: TenantId,
                    docUuid: String,
                    version: Int,
                ): LockedSubmission? {
                    check(++locks == 1) { "forced after the event" }
                    return f.store.lockForUpdate(tenant, docUuid, version)
                }
            }

        runCatching { f.applier(over = failing).apply(dispatch, rejection(ErrorCode.SUB_003.code)) }
            .isFailure shouldBe true

        f.stateOf(tenant, doc, 1) shouldBe LifecycleState.SENT.name
        f.classes(tenant, doc) shouldBe listOf(null)
        f.events(tenant, doc) shouldBe emptyList()
    }
}
