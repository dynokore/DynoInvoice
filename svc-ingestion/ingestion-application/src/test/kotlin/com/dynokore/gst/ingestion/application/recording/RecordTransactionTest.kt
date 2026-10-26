package com.dynokore.gst.ingestion.application.recording

import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The idempotency pair at the service boundary (INV-REQ-806), against a stub of the canonical `recordDocument`
 * operation: the stub answers as the canonical contract states (`201` a recorded version, `200` the replay, ING-002
 * for a lower revision), keeping the state canonical-svc owns. ingestion-svc itself keeps none.
 */
class RecordTransactionTest {
    private class CanonicalStub : CanonicalRecorder {
        private class Known(
            val docUuid: UUID,
            var revision: Long,
            var version: Long,
        )

        private val known = mutableMapOf<String, Known>()
        val calls = mutableListOf<RecordDocumentCommand>()

        override fun record(command: RecordDocumentCommand): CanonicalAnswer {
            calls += command
            val key = "${command.tenantId}/${command.sourceSystem}/${command.sourceKey}"
            val recorded = known[key]
            return when {
                recorded == null -> {
                    val created = Known(UUID.randomUUID(), command.sourceRevision, 1)
                    known[key] = created
                    CanonicalAnswer.Created(created.docUuid, created.version)
                }

                command.sourceRevision == recorded.revision -> {
                    CanonicalAnswer.Replay(recorded.docUuid, recorded.version)
                }

                command.sourceRevision > recorded.revision -> {
                    recorded.revision = command.sourceRevision
                    recorded.version += 1
                    CanonicalAnswer.Created(recorded.docUuid, recorded.version)
                }

                else -> {
                    CanonicalAnswer.Rejected("ING-002", 409)
                }
            }
        }
    }

    private val tenant = UUID.randomUUID()

    private fun command(
        key: String,
        revision: Long,
        body: String = "{}",
    ) = RecordDocumentCommand(tenant, "erp-acme", key, revision, body)

    @Test
    fun `INV-AC-1861 replay answers 200 with the original Doc UUID, higher amends, lower is 409`() {
        val canonical = CanonicalStub()
        val service = RecordTransaction(canonical)
        val first = service.execute(command("K", 2)) as RecordingOutcome.New
        first.httpStatus shouldBe 201
        val replay = service.execute(command("K", 2))
        replay shouldBe RecordingOutcome.Replay(first.docUuid)
        replay.httpStatus shouldBe 200
        val amend = service.execute(command("K", 3)) as RecordingOutcome.Amend
        amend.docUuid shouldBe first.docUuid
        amend.version shouldBe 2
        amend.httpStatus shouldBe 200
        val stale = service.execute(command("K", 1))
        stale shouldBe RecordingOutcome.Stale
        stale.httpStatus shouldBe 409
        RecordingOutcome.Stale.ERROR_CODE shouldBe "ING-002"
    }

    @Test
    fun `INV-AC-10334 same pair replays, higher revision amends the same Doc UUID, lower is ING-002`() {
        val canonical = CanonicalStub()
        val service = RecordTransaction(canonical)
        val first = service.execute(command("K", 1)) as RecordingOutcome.New
        service.execute(command("K", 1)) shouldBe RecordingOutcome.Replay(first.docUuid)
        val amended = service.execute(command("K", 2, body = """{"changed":true}""")) as RecordingOutcome.Amend
        amended.docUuid shouldBe first.docUuid
        (amended.version > 1) shouldBe true
        service.execute(command("K", 1)) shouldBe RecordingOutcome.Stale
        val other = service.execute(command("K2", 1)) as RecordingOutcome.New
        (other.docUuid != first.docUuid) shouldBe true
        canonical.calls.size shouldBe 5
    }

    @Test
    fun `a replay returns the Doc UUID the canonical stub returned`() {
        val docUuid = UUID.randomUUID()
        val service = RecordTransaction { CanonicalAnswer.Replay(docUuid, 1) }
        service.execute(command("K", 1)) shouldBe RecordingOutcome.Replay(docUuid)
    }

    @Test
    fun `canonical 201 with version 1 is a new document and with a higher version is an amendment`() {
        val docUuid = UUID.randomUUID()
        RecordTransaction { CanonicalAnswer.Created(docUuid, 1) }.execute(command("K", 1)) shouldBe
            RecordingOutcome.New(docUuid)
        RecordTransaction { CanonicalAnswer.Created(docUuid, 2) }.execute(command("K", 2)) shouldBe
            RecordingOutcome.Amend(docUuid, 2)
    }

    @Test
    fun `INV-AC-1862 a document held on MDM-001 is answered 202 with the Doc UUID, version and reason`() {
        val docUuid = UUID.randomUUID()
        val outcome =
            RecordTransaction { CanonicalAnswer.Held(docUuid, 1, listOf("MDM-001")) }.execute(command("K", 1))
        outcome shouldBe RecordingOutcome.Held(docUuid, 1, listOf("MDM-001"))
        outcome.httpStatus shouldBe 202
    }

    @Test
    fun `INV-AC-2519 a blocked document is answered 202 with the block reason RUL-001`() {
        val docUuid = UUID.randomUUID()
        val outcome =
            RecordTransaction { CanonicalAnswer.Held(docUuid, 1, listOf("RUL-001")) }.execute(command("K", 1))
        outcome shouldBe RecordingOutcome.Held(docUuid, 1, listOf("RUL-001"))
        outcome.httpStatus shouldBe 202
    }
}
