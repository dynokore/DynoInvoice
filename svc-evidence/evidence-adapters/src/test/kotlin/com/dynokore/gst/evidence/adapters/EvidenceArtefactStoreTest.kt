package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-20306 (INV-REQ-1036 behavior 2): `EvidenceArtefactRecorded` is stored once per (tenant, type, reference,
 * hash), an unknown type as received, a malformed event not at all, and the table is append-only and tenant-isolated.
 */
class EvidenceArtefactStoreTest {
    private val hashA = "a".repeat(HASH_LENGTH)
    private val hashB = "b".repeat(HASH_LENGTH)

    private fun envelope(
        tenant: UUID,
        eventId: String,
        data: String,
    ) = Envelope(
        id = EventId(eventId),
        source = "/gst/payload-svc",
        type = "com.dynokore.gst.platform.EvidenceArtefactRecorded.v1",
        subject = "s",
        time = Instant.parse("2026-01-15T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EventEntityRef("ENT-3F2A9C"),
        docUuid = DocUuid(UUID.randomUUID()),
        transactionDate = LocalDate.of(2026, 1, 15),
        docSequence = 1,
        instanceId = "payload-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun data(
        type: String = "SAMPLE_CHECK",
        reference: String = "sample-1",
        hash: String = hashA,
    ) = """{"artefactType":"$type","reference":"$reference","hash":"$hash"}"""

    /** Handles [events] in one runtime transaction bound to [tenant]; the outcomes, or the first failure thrown. */
    private fun handle(
        tenant: UUID,
        vararg events: Envelope,
    ): List<HandleOutcome> =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            val handler = ChainWiring.evidenceArtefactHandler(DSL.using(connection, SQLDialect.POSTGRES))
            try {
                events.map { handler.handle(it) }.also { connection.commit() }
            } catch (e: ContractViolation) {
                connection.rollback()
                throw e
            }
        }

    private fun rows(tenant: UUID): List<Triple<String, String, String>> =
        EvidenceDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select artefact_type, reference, hash from evidence.evidence_artefact " +
                        "where tenant_id = ? order by recorded_at, reference, hash",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                Triple(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun rejected(
        tenant: UUID,
        data: String,
    ) {
        shouldThrow<ContractViolation> { handle(tenant, envelope(tenant, "e-bad", data)) }
        rows(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20306 the same type, reference and hash twice stores one row`() {
        val tenant = UUID.randomUUID()
        handle(tenant, envelope(tenant, "e1", data()), envelope(tenant, "e2", data())) shouldBe
            listOf(HandleOutcome.APPLIED, HandleOutcome.APPLIED)
        rows(tenant) shouldBe listOf(Triple("SAMPLE_CHECK", "sample-1", hashA))
    }

    @Test
    fun `INV-AC-20306 the same reference with another hash stores two rows`() {
        val tenant = UUID.randomUUID()
        handle(tenant, envelope(tenant, "e1", data()), envelope(tenant, "e2", data(hash = hashB)))
        rows(tenant).map { it.third }.toSet() shouldBe setOf(hashA, hashB)
    }

    @Test
    fun `INV-AC-20306 an artefact type outside the known set is stored as received`() {
        val tenant = UUID.randomUUID()
        handle(tenant, envelope(tenant, "e1", data(type = "NEW_KIND")))
        rows(tenant) shouldBe listOf(Triple("NEW_KIND", "sample-1", hashA))
    }

    @Test
    fun `INV-AC-20306 the entity of the envelope is stored as entity_ref`() {
        val tenant = UUID.randomUUID()
        handle(tenant, envelope(tenant, "e1", data()))
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it
                    .executeQuery(
                        "select entity_ref, event_id from evidence.evidence_artefact where tenant_id = '$tenant'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "ENT-3F2A9C"
                        rs.getString(2) shouldBe "e1"
                    }
            }
        }
    }

    @Test
    fun `INV-AC-20306 a lower case look-alike type raises ContractViolation and stores nothing`() {
        rejected(UUID.randomUUID(), data(type = "sample_check"))
    }

    @Test
    fun `INV-AC-20306 a 63 character hash raises ContractViolation and stores nothing`() {
        rejected(UUID.randomUUID(), data(hash = "a".repeat(HASH_LENGTH - 1)))
    }

    @Test
    fun `INV-AC-20306 a hash with an upper case letter raises ContractViolation and stores nothing`() {
        rejected(UUID.randomUUID(), data(hash = "A" + "a".repeat(HASH_LENGTH - 1)))
    }

    @Test
    fun `INV-AC-20306 a missing field or a non text field raises ContractViolation and stores nothing`() {
        val tenant = UUID.randomUUID()
        rejected(tenant, """{"reference":"r","hash":"$hashA"}""")
        rejected(tenant, """{"artefactType":"RENDITION","hash":"$hashA"}""")
        rejected(tenant, """{"artefactType":"RENDITION","reference":"r"}""")
        rejected(tenant, """{"artefactType":"RENDITION","reference":"r","hash":null}""")
        rejected(tenant, """{"artefactType":7,"reference":"r","hash":"$hashA"}""")
        rejected(tenant, """{"artefactType":"RENDITION","reference":" ","hash":"$hashA"}""")
        rejected(tenant, "not json")
    }

    private fun refusedAs(
        login: String,
        password: String,
        tenant: UUID,
        sql: String,
    ): SQLException =
        EvidenceDb.connect(login, password).use { connection: Connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            val failure = runCatching { connection.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            connection.rollback()
            failure as SQLException
        }

    @Test
    fun `INV-AC-20306 UPDATE and DELETE are refused, by the grant for the runtime role and by CS-22 for the owner`() {
        val tenant = UUID.randomUUID()
        handle(tenant, envelope(tenant, "e1", data()))
        listOf("update evidence.evidence_artefact set reference = 'x'", "delete from evidence.evidence_artefact")
            .forEach { sql ->
                refusedAs(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD, tenant, sql).sqlState shouldBe "42501"
                refusedAs(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD, tenant, sql).message!! shouldContain "CS-22"
            }
        refusedAs(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD, tenant, "truncate evidence.evidence_artefact")
            .sqlState shouldBe "42501"
        rows(tenant).size shouldBe 1
    }

    @Test
    fun `INV-AC-20306 a row of tenant A is invisible when tenant B is bound`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        handle(a, envelope(a, "e1", data()))
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, b)
            connection.createStatement().use {
                it.executeQuery("select count(*) from evidence.evidence_artefact").use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
            connection.rollback()
        }
    }

    private companion object {
        const val HASH_LENGTH = 64
    }
}
