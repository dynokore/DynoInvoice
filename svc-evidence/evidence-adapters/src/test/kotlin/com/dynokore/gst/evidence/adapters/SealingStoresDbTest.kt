package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqHeldPayloadStore
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqParkedDespatchStore
import com.dynokore.gst.evidence.adapters.persistence.JooqPayloadQuarantineStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSealedPayloadSink
import com.dynokore.gst.evidence.application.chain.LocatorStore
import com.dynokore.gst.evidence.application.payload.HeldPayloadStore
import com.dynokore.gst.evidence.application.payload.ParkedDespatch
import com.dynokore.gst.evidence.application.payload.ParkedDespatchStore
import com.dynokore.gst.evidence.application.payload.PayloadQuarantineStore
import com.dynokore.gst.evidence.application.payload.QuarantineOrigin
import com.dynokore.gst.evidence.application.payload.SealedPayloadSink
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.payload.Despatch
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * INV-AC-20566 (INV-REQ-645 behavior 1-2): the database adapters of the sealing ports and the `sealed_payload` link
 * written with the append. Every store and the sink are assigned to their port type, so the conformance is checked by
 * the compiler.
 */
class SealingStoresDbTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000001")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
    private val sha = "a".repeat(SHA_LENGTH)

    private fun held(
        version: Int = 1,
        bytes: String = "<Invoice/>",
    ) = HeldPayload(doc, version, bytes.toByteArray(), sha)

    private fun despatch(attempt: Int) = Despatch(doc, 1, attempt, sha, at)

    /** The stores of one tenant-bound transaction, typed by their ports. */
    private class Stores(
        val dsl: DSLContext,
    ) {
        val held: HeldPayloadStore = JooqHeldPayloadStore(dsl)
        val parked: ParkedDespatchStore = JooqParkedDespatchStore(dsl)
        val quarantine: PayloadQuarantineStore = JooqPayloadQuarantineStore(dsl)
        val sink: SealedPayloadSink = JooqSealedPayloadSink(dsl, ChainWiring.recordAppender(dsl))
        val locators: LocatorStore = JooqLocatorStore(dsl)
    }

    private fun open(tenant: UUID): Connection =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).also {
            it.autoCommit = false
            SignedBindings.bind(it, tenant)
        }

    private fun <T> tx(
        tenant: UUID,
        body: Stores.(TenantId) -> T,
    ): T =
        open(tenant).use { connection ->
            val result = Stores(DSL.using(connection, SQLDialect.POSTGRES)).body(TenantId(tenant))
            connection.commit()
            result
        }

    private fun count(
        sql: String,
        tenant: UUID,
    ): Int =
        EvidenceDb.superuser().use { c ->
            c.createStatement().use {
                it.executeQuery(sql.replace("?", "'$tenant'")).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `INV-AC-20566 held insert returns true then false and keeps the first payload`() {
        val t = UUID.randomUUID()
        tx(t) { tenant ->
            held.insert(tenant, "e1", held(bytes = "first"), at) shouldBe true
            held.insert(tenant, "e2", held(bytes = "second"), at) shouldBe false
            String(checkNotNull(held.find(tenant, doc, 1)).payloadBytes()) shouldBe "first"
        }
    }

    @Test
    fun `INV-AC-20566 find returns the payload for its version and null for another version or tenant`() {
        val t = UUID.randomUUID()
        tx(t) { tenant ->
            held.insert(tenant, "e1", held(), at)
            val found = checkNotNull(held.find(tenant, doc, 1))
            String(found.payloadBytes()) shouldBe "<Invoice/>"
            found.payloadSha256 shouldBe sha
            held.find(tenant, doc, 2) shouldBe null
        }
        tx(UUID.randomUUID()) { tenant -> held.find(tenant, doc, 1) shouldBe null }
    }

    @Test
    fun `INV-AC-20566 held remove then insert succeeds`() {
        val t = UUID.randomUUID()
        tx(t) { tenant ->
            held.insert(tenant, "e1", held(bytes = "corrupt"), at) shouldBe true
            held.remove(tenant, doc, 1)
            held.find(tenant, doc, 1) shouldBe null
            held.insert(tenant, "e2", held(bytes = "replacement"), at) shouldBe true
            String(checkNotNull(held.find(tenant, doc, 1)).payloadBytes()) shouldBe "replacement"
        }
    }

    private fun <T> Future<T>.completesWithin(millis: Long): Boolean =
        runCatching { get(millis, TimeUnit.MILLISECONDS) }.isSuccess

    @Test
    fun `INV-AC-20566 the held lock blocks the same version only until the holder commits`() {
        val t = UUID.randomUUID()
        val other = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(POOL)
        val connections = mutableListOf<Connection>()

        fun conn(tenant: UUID) = open(tenant).also { connections += it }

        fun lockOn(
            connection: Connection,
            body: Stores.(TenantId) -> Unit,
            tenant: UUID,
        ): Future<Unit> =
            pool.submit(Callable { Stores(DSL.using(connection, SQLDialect.POSTGRES)).body(TenantId(tenant)) })
        try {
            val a = conn(t)
            Stores(DSL.using(a, SQLDialect.POSTGRES)).held.lock(TenantId(t), doc, 1)

            val sameVersion = lockOn(conn(t), { tenant -> held.lock(tenant, doc, 1) }, t)
            val otherVersion = lockOn(conn(t), { tenant -> held.lock(tenant, doc, 2) }, t)
            val otherTenant = lockOn(conn(other), { tenant -> held.lock(tenant, doc, 1) }, other)
            val locator = lockOn(conn(t), { tenant -> locators.lock(tenant, doc, 1) }, t)

            otherVersion.completesWithin(RETURN_AT_ONCE_MILLIS) shouldBe true
            otherTenant.completesWithin(RETURN_AT_ONCE_MILLIS) shouldBe true
            locator.completesWithin(RETURN_AT_ONCE_MILLIS) shouldBe true
            Thread.sleep(BLOCKED_WAIT_MILLIS)
            sameVersion.isDone shouldBe false

            a.commit()
            sameVersion.completesWithin(RETURN_AT_ONCE_MILLIS) shouldBe true
        } finally {
            pool.shutdownNow()
            connections.forEach { runCatching { it.rollback() } }
            connections.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `INV-AC-20566 park twice stores one row and parkedFor keeps the order of arrival`() {
        val t = UUID.randomUUID()
        tx(t) { tenant ->
            parked.park(tenant, ParkedDespatch("e2", despatch(2))) shouldBe true
            parked.park(tenant, ParkedDespatch("e2", despatch(2))) shouldBe false
            parked.park(tenant, ParkedDespatch("e1", despatch(1))) shouldBe true
            parked.parkedFor(tenant, doc, 1).map { it.eventId } shouldBe listOf("e2", "e1")
            parked.parkedFor(tenant, doc, 1).first().despatch shouldBe despatch(2)
            parked.parkedFor(tenant, doc, 2) shouldBe emptyList()
        }
        count("select count(*) from evidence.parked_despatch where tenant_id = ?", t) shouldBe 2
    }

    @Test
    fun `INV-AC-20566 quarantine twice stores one row and the other origin stores a second`() {
        val t = UUID.randomUUID()
        tx(t) { tenant ->
            quarantine.quarantine(tenant, "c1", held(), QuarantineOrigin.RECEIVED, at)
            quarantine.quarantine(tenant, "c1", held(), QuarantineOrigin.RECEIVED, at)
        }
        count("select count(*) from evidence.payload_quarantine where tenant_id = ?", t) shouldBe 1
        tx(t) { tenant -> quarantine.quarantine(tenant, "c1", held(), QuarantineOrigin.STORED, at) }
        count("select count(*) from evidence.payload_quarantine where tenant_id = ?", t) shouldBe 2
    }

    private fun locate(
        t: UUID,
        version: Int = 1,
    ) = tx(t) { tenant ->
        locators.insert(
            tenant,
            "loc-$version",
            DocumentLocator(doc, version, "ENT-3F2A9C", "2026-Q1", LocalDate.of(2026, 1, 15), "trace-1", "INV-001"),
            at,
        )
    }

    @Test
    fun `INV-AC-20566 the sink appends one record and one link and a repeat of the event writes neither`() {
        val t = UUID.randomUUID()
        locate(t)
        repeat(2) { tx(t) { tenant -> sink.append(tenant, doc, 1, "E1", """{"id":"E1"}""".toByteArray(), at) } }
        count("select count(*) from evidence.evidence_payload where tenant_id = ?", t) shouldBe 1
        count("select count(*) from evidence.sealed_payload where tenant_id = ?", t) shouldBe 1
        count("select count(*) from evidence.sealed_payload where tenant_id = ? and source_event_id = 'E1'", t) shouldBe 1
    }

    @Test
    fun `INV-AC-20566 a second tenant reads none of the rows`() {
        val t = UUID.randomUUID()
        locate(t)
        tx(t) { tenant ->
            held.insert(tenant, "e1", held(), at)
            parked.park(tenant, ParkedDespatch("p1", despatch(1)))
            quarantine.quarantine(tenant, "c1", held(), QuarantineOrigin.RECEIVED, at)
            sink.append(tenant, doc, 1, "E1", """{"id":"E1"}""".toByteArray(), at)
        }
        tx(UUID.randomUUID()) { tenant ->
            held.find(tenant, doc, 1) shouldBe null
            parked.parkedFor(tenant, doc, 1) shouldBe emptyList()
            listOf("held_payload", "parked_despatch", "payload_quarantine", "sealed_payload").forEach {
                dsl.fetchCount(DSL.table(DSL.name("evidence", it))) shouldBe 0
            }
        }
    }

    private fun refusedByOwner(
        tenant: UUID,
        sql: String,
    ): SQLException =
        EvidenceDb.connect(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            val failure = runCatching { connection.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            connection.rollback()
            failure as SQLException
        }

    @Test
    fun `INV-AC-20566 UPDATE and DELETE of the three append-only tables fail with CS-22`() {
        val t = UUID.randomUUID()
        locate(t)
        tx(t) { tenant ->
            parked.park(tenant, ParkedDespatch("p1", despatch(1)))
            quarantine.quarantine(tenant, "c1", held(), QuarantineOrigin.RECEIVED, at)
            sink.append(tenant, doc, 1, "E1", """{"id":"E1"}""".toByteArray(), at)
        }
        mapOf(
            "sealed_payload" to "version = 9",
            "parked_despatch" to "version = 9",
            "payload_quarantine" to "version = 9",
        ).forEach { (table, set) ->
            refusedByOwner(t, "update evidence.$table set $set").message!! shouldContain "CS-22"
            refusedByOwner(t, "delete from evidence.$table").message!! shouldContain "CS-22"
        }
    }

    @Test
    fun `INV-AC-20566 evidence_runtime has no DELETE on the three tables and has DELETE on held_payload`() {
        fun has(
            table: String,
            privilege: String,
        ): Boolean =
            EvidenceDb.superuser().use { c ->
                c.createStatement().use {
                    it
                        .executeQuery(
                            "select has_table_privilege('evidence_runtime', 'evidence.$table', '$privilege')",
                        ).use { rs ->
                            rs.next()
                            rs.getBoolean(1)
                        }
                }
            }
        listOf("sealed_payload", "parked_despatch", "payload_quarantine").forEach {
            has(it, "DELETE") shouldBe false
            has(it, "UPDATE") shouldBe false
        }
        has("held_payload", "DELETE") shouldBe true
        has("held_payload", "UPDATE") shouldBe false
    }

    private companion object {
        const val SHA_LENGTH = 64
        const val POOL = 6
        const val RETURN_AT_ONCE_MILLIS = 5_000L
        const val BLOCKED_WAIT_MILLIS = 500L
    }
}
