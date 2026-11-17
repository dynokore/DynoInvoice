package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.events.EvidenceArtefactOutbox
import com.dynokore.gst.reconciliation.adapters.persistence.JooqSealedWorkPapers
import com.dynokore.gst.reconciliation.application.workpaper.SealWorkPaper
import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperReference
import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperSeal
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * INV-AC-24183 at the database: a sealed work-paper is written once under its tenant-scoped key and indexed by an
 * append-only seal row (INV-T-24187). The object store is a fake that records each put and can be set to throw.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WorkPaperSealDbTest {
    private class FakeStore : ObjectStore {
        class Put(
            val key: String,
            val bytes: ByteArray,
            val retainUntil: Instant,
        )

        val puts = mutableListOf<Put>()
        var failure: RuntimeException? = null

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            failure?.let { throw it }
            puts += Put(key.value, bytes, retainUntil)
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = throw UnsupportedOperationException()
    }

    private class FakeRecorder : EvidenceArtefactRecorder {
        val recorded = mutableListOf<Pair<EvidenceArtefact, EventContext>>()

        override fun record(
            artefact: EvidenceArtefact,
            context: EventContext,
        ) {
            recorded += artefact to context
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneOffset.UTC)
    private val b1 = "{\"box\":1}".toByteArray()
    private val b2 = "{\"box\":2}".toByteArray()
    private val h1 = WorkPaperSeal.of(b1).sha256Hex
    private val h2 = WorkPaperSeal.of(b2).sha256Hex

    private fun newTenant() = UUID.randomUUID()

    private fun <T> inTenant(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                block(connection).also { connection.commit() }
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun store(
        connection: Connection,
        fake: FakeStore,
    ) = JooqSealedWorkPapers(DSL.using(connection, SQLDialect.POSTGRES), fake, clock, RETENTION_DAYS)

    private fun sealRows(
        tenant: UUID,
        runId: String,
    ): List<List<Any?>> =
        ReconciliationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select seal_version, reference, sha256, sealed_at, entity_ref " +
                        "from reconciliation.work_paper_seal where tenant_id = ? and run_id = ? order by seal_version",
                ).use { st ->
                    st.setObject(1, tenant)
                    st.setString(2, runId)
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    listOf(
                                        rs.getInt(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getObject(4, java.time.OffsetDateTime::class.java).toInstant(),
                                        rs.getString(5),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    @Test
    fun `INV-AC-24183 a sealed work-paper is written once under its key and indexed by one seal row`() {
        val t1 = newTenant()
        val fake = FakeStore()
        val key = WorkPaperReference.of(t1.toString(), "R1", 1)
        val returned = inTenant(t1) { store(it, fake).put(t1.toString(), "R1", 1, b1) }
        returned shouldBe key
        fake.puts.size shouldBe 1
        fake.puts[0].key shouldBe key
        fake.puts[0].bytes.contentEquals(b1) shouldBe true
        fake.puts[0].retainUntil shouldBe clock.instant().plus(Duration.ofDays(RETENTION_DAYS))
        val rows = sealRows(t1, "R1")
        rows.size shouldBe 1
        rows[0][0] shouldBe 1
        rows[0][1] shouldBe key
        rows[0][2] shouldBe h1
        (rows[0][2] as String).matches(Regex("^[0-9a-f]{64}$")) shouldBe true
        rows[0][3] shouldBe clock.instant()
    }

    @Test
    fun `INV-AC-24183 latest returns the sealed reference to its tenant only`() {
        val t1 = newTenant()
        val t2 = newTenant()
        inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b1) }
        val latest = inTenant(t1) { store(it, FakeStore()).latest(t1.toString(), "R1") }
        latest?.reference shouldBe WorkPaperReference.of(t1.toString(), "R1", 1)
        latest?.sha256Hex shouldBe h1
        latest?.sealVersion shouldBe 1
        inTenant(t2) { store(it, FakeStore()).latest(t2.toString(), "R1") } shouldBe null
    }

    @Test
    fun `INV-AC-24183 latest names the highest seal version of the run`() {
        val t1 = newTenant()
        inTenant(t1) {
            val sealed = store(it, FakeStore())
            sealed.put(t1.toString(), "R1", 1, b1)
            sealed.put(t1.toString(), "R1", 2, b2)
        }
        val latest = inTenant(t1) { store(it, FakeStore()).latest(t1.toString(), "R1") }
        latest?.sealVersion shouldBe 2
        latest?.sha256Hex shouldBe h2
    }

    @Test
    fun `INV-AC-24183 latest does not read the seal row of another tenant even with a bound foreign tenant`() {
        val t1 = newTenant()
        val t2 = newTenant()
        inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b1) }
        // the binding is T2 and the query asks for T1: the tenant predicate and the row-level security both refuse it
        inTenant(t2) { store(it, FakeStore()).latest(t1.toString(), "R1") } shouldBe null
        inTenant(t2) { connection ->
            connection.prepareStatement("select count(*) from reconciliation.work_paper_seal").use { st ->
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        } shouldBe 0
    }

    @Test
    fun `INV-AC-24183 latest carries its own tenant predicate when row-level security is bypassed`() {
        val t1 = newTenant()
        val t2 = newTenant()
        inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b1) }
        // the superuser bypasses row-level security: only the predicate of the query keeps T1's row from T2
        ReconciliationDb.superuser().use { store(it, FakeStore()).latest(t2.toString(), "R1") } shouldBe null
        ReconciliationDb.superuser().use { store(it, FakeStore()).latest(t1.toString(), "R1") }?.sha256Hex shouldBe h1
    }

    @Test
    fun `INV-AC-24183 a second put of a committed version is refused and the row keeps the first hash`() {
        val t1 = newTenant()
        inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b1) }
        shouldThrow<Exception> { inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b2) } }
        val rows = sealRows(t1, "R1")
        rows.size shouldBe 1
        rows[0][2] shouldBe h1
    }

    @Test
    fun `INV-AC-24183 a store that throws leaves no seal row and the exception propagates`() {
        val t1 = newTenant()
        val fake = FakeStore().also { it.failure = IllegalStateException("store down") }
        val thrown =
            shouldThrow<IllegalStateException> {
                inTenant(t1) { store(it, fake).put(t1.toString(), "R1", 1, b1) }
            }
        thrown.message shouldBe "store down"
        sealRows(t1, "R1") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24183 an update and a delete of the seal row are refused for the runtime login`() {
        val t1 = newTenant()
        inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b1) }
        shouldThrow<SQLException> {
            inTenant(t1) {
                it.prepareStatement("update reconciliation.work_paper_seal set sha256 = ?").use { st ->
                    st.setString(1, h2)
                    st.executeUpdate()
                }
            }
        }
        shouldThrow<SQLException> {
            inTenant(t1) { deleteAll(it) }
        }
        sealRows(t1, "R1")[0][2] shouldBe h1
    }

    @Test
    fun `INV-AC-24183 the append-only trigger refuses an update and a delete even for the superuser`() {
        val t1 = newTenant()
        inTenant(t1) { store(it, FakeStore()).put(t1.toString(), "R1", 1, b1) }
        ReconciliationDb.superuser().use { connection ->
            shouldThrow<SQLException> {
                connection.prepareStatement("update reconciliation.work_paper_seal set sha256 = ?").use { st ->
                    st.setString(1, h2)
                    st.executeUpdate()
                }
            }
            shouldThrow<SQLException> {
                deleteAll(connection)
            }
        }
        sealRows(t1, "R1")[0][2] shouldBe h1
    }

    @Test
    fun `INV-AC-24183 the publisher hands the recorder the three artefact fields and the entity`() {
        val t1 = newTenant()
        val recorder = FakeRecorder()
        val reference = WorkPaperReference.of(t1.toString(), "R1", 1)
        EvidenceArtefactOutbox(recorder).publish(t1.toString(), "E1", SealWorkPaper.ARTEFACT_TYPE, reference, h1)
        recorder.recorded.size shouldBe 1
        val (artefact, context) = recorder.recorded[0]
        artefact.type.value shouldBe "RECONCILIATION_WORK_PAPER"
        artefact.reference shouldBe reference
        artefact.hash shouldBe h1
        context.entity.value shouldBe "E1"
        context.tenant.value shouldBe t1
        context.aggregateKey shouldBe reference
    }

    private fun deleteAll(connection: Connection) =
        connection.prepareStatement("delete from reconciliation.work_paper_seal").use { it.executeUpdate() }

    private companion object {
        const val RETENTION_DAYS = 30L
    }
}
