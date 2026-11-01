package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationRecordsJooq
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.SandboxAcknowledgement
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The accreditation record table as the runtime login (CS-19): append-only, the user ID of the caller, fail closed. */
class AccreditationRecordIT {
    private val dsl = PostgresDb.dsl()
    private val probe = PostgresDb.probe
    private val repository = AccreditationRecordsJooq(dsl)

    private fun recordOf(
        reference: String,
        actor: String,
        at: String,
        until: String = "2027-09-14",
    ) = AccreditationRecord(
        LocalDate.parse("2026-09-15"),
        LocalDate.parse(until),
        reference,
        SubmissionType.entries,
        SubmissionType.entries.map { SandboxAcknowledgement(it, "ACK-${it.name}") },
        Instant.parse(at),
        ActorId(actor),
    )

    private fun count() = dsl.fetchValue("select count(*) from controlplane.accreditation_record")?.toString()?.toLong()

    private fun has(privilege: String) =
        probe.fetchValue(
            "select has_table_privilege(current_user, ?, ?)",
            "controlplane.accreditation_record",
            privilege,
        ) ==
            true

    @Test
    fun `a record round-trips with its four acknowledgements and the actor string`() {
        val reference = "IMDA-" + UUID.randomUUID()
        val record = recordOf(reference, "alice@operator.example", "2026-10-19T08:00:00Z")
        repository.save(record)
        repository.find(reference, record.currentUntil) shouldBe record
    }

    @Test
    fun `INV-AC-2339 the four acknowledgement ids come back from the table with their types`() {
        val reference = "IMDA-" + UUID.randomUUID()
        repository.save(recordOf(reference, "alice", "2026-10-19T08:00:00Z"))
        repository
            .find(reference, LocalDate.parse("2027-09-14"))!!
            .sandboxAcknowledgements
            .map { it.submissionType to it.acknowledgementId } shouldBe
            SubmissionType.entries.map { it to "ACK-${it.name}" }
    }

    @Test
    fun `a second record leaves two rows, the latest recorded_at is the status and the repeat stores nothing`() {
        val before = count()!!
        val first = recordOf("IMDA-" + UUID.randomUUID(), "alice", "2099-01-01T00:00:00Z", until = "2100-01-01")
        val second = recordOf("IMDA-" + UUID.randomUUID(), "bob", "2099-01-02T00:00:00Z", until = "2101-01-01")
        repository.save(first)
        repository.save(second)
        repository.save(second) // the same (reference, currentUntil): nothing is stored
        count() shouldBe before + 2
        repository.latest() shouldBe second
        repository.find(first.imdaReference, first.currentUntil) shouldBe first // the first stays readable
    }

    @Test
    fun `recorded_by NULL or empty is refused by the table`() {
        listOf<String?>(null, "", "a".repeat(65)).forEach { actor ->
            shouldThrow<DataAccessException> {
                dsl.execute(
                    "insert into controlplane.accreditation_record(id, granted_on, current_until, imda_reference, " +
                        "covered_submission_types, sandbox_acknowledgements, recorded_at, recorded_by) " +
                        "values (?, date '2026-09-15', date '2027-09-14', ?, array['TYPE_1A'], '[]'::jsonb, now(), ?)",
                    UUID.randomUUID(),
                    "IMDA-" + UUID.randomUUID(),
                    actor,
                )
            }
        }
    }

    @Test
    fun `the runtime login holds no UPDATE and no DELETE on the table`() {
        has("UPDATE") shouldBe false
        has("DELETE") shouldBe false
        has("INSERT") shouldBe true
    }
}
