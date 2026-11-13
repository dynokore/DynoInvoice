package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MasterDataResolvedHandlerTest {
    private class MemoryStore : ExceptionStore {
        val rows = linkedMapOf<Pair<String, UUID>, ExceptionRow>()
        var writes = 0

        override fun upsert(rows: List<ExceptionRow>) {
            writes++
            rows.forEach { this.rows[it.tenantId to it.exceptionId] = it }
        }

        override fun rowsOfDocument(
            tenantId: String,
            docUuid: String,
        ) = rows.values.filter { it.tenantId == tenantId && it.docUuid == docUuid }

        override fun openRowsOfDocument(
            tenantId: String,
            docUuid: String,
        ) = rowsOfDocument(tenantId, docUuid).filter { it.state == ItemState.OPEN }
    }

    private val tenantId = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val tenant = tenantId.value.toString()
    private val d = (1..6).map { "00000000-0000-0000-0000-00000000000$it" }
    private val store = MemoryStore()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenantId

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    /** dataJson "bad" is malformed, "none" lists nothing, otherwise a comma list of doc uuids (duplicates kept). */
    private val reader =
        object : MasterDataResolvedReader {
            override fun read(dataJson: String): List<String> {
                if (dataJson == "bad") throw ContractViolation("malformed")
                if (dataJson == "none") return emptyList()
                return dataJson.split(',').distinct()
            }
        }

    private val handler = MasterDataResolvedHandler(tenants, reader, store, RecordDocumentReleased(store))

    private fun seed(
        doc: String,
        code: String,
        state: ItemState,
    ) {
        val row =
            ExceptionRow(
                tenant,
                UUID.nameUUIDFromBytes("$doc/$code/$state".toByteArray()),
                "ENT-1",
                doc,
                code,
                "",
                "",
                state,
                Instant.parse("2026-11-12T08:00:00Z"),
                "2026-Q3",
                1L,
            )
        store.upsert(listOf(row))
    }

    private fun state(
        doc: String,
        code: String,
    ) = store.rows.values
        .single { it.docUuid == doc && it.code == code }
        .state

    private fun envelope(data: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/masterdata-svc",
            type = "com.dynokore.gst.masterdata.MasterDataResolved.v1",
            subject = "party",
            time = Instant.parse("2026-11-12T09:00:00Z"),
            tenant = tenantId,
            entity = EntityRef("ENT-1"),
            docUuid = DocUuid(UUID.fromString(d[0])),
            transactionDate = null,
            docSequence = 1,
            instanceId = "masterdata-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun seedAll() {
        seed(d[0], "MDM-001", ItemState.OPEN)
        seed(d[0], "MDM-002", ItemState.OPEN)
        seed(d[0], "PAY-001", ItemState.OPEN)
        seed(d[1], "MDM-001", ItemState.OPEN)
        seed(d[2], "MDM-001", ItemState.RESOLVED)
        seed(d[3], "MDM-001", ItemState.OVERRIDDEN)
        seed(d[5], "PAY-001", ItemState.OPEN)
        seed(d[5], "mdm-001", ItemState.OPEN)
    }

    private fun listed() = listOf(d[0], d[2], d[3], d[4], d[5]).joinToString(",")

    @Test
    fun `INV-AC-21062 resolves only the master-data hold rows of listed documents and no others`() {
        seedAll()
        handler.handle(envelope(listed())) shouldBe HandleOutcome.APPLIED
        state(d[0], "MDM-001") shouldBe ItemState.RESOLVED
        state(d[0], "MDM-002") shouldBe ItemState.OPEN
        state(d[0], "PAY-001") shouldBe ItemState.OPEN
        state(d[1], "MDM-001") shouldBe ItemState.OPEN
        state(d[2], "MDM-001") shouldBe ItemState.RESOLVED
        state(d[3], "MDM-001") shouldBe ItemState.OVERRIDDEN
        state(d[5], "PAY-001") shouldBe ItemState.OPEN
        state(d[5], "mdm-001") shouldBe ItemState.OPEN
    }

    @Test
    fun `INV-AC-21062 the same envelope again changes nothing and is ignored`() {
        seedAll()
        handler.handle(envelope(listed()))
        val before = store.rows.toMap()
        val writes = store.writes
        handler.handle(envelope(listed())) shouldBe HandleOutcome.IGNORED
        store.rows shouldBe before
        store.writes shouldBe writes
    }

    @Test
    fun `INV-AC-21062 an envelope without docUuids changes nothing and is ignored`() {
        seedAll()
        val before = store.rows.toMap()
        handler.handle(envelope("none")) shouldBe HandleOutcome.IGNORED
        store.rows shouldBe before
    }

    @Test
    fun `INV-AC-21062 an envelope listing only an already resolved document is ignored`() {
        seedAll()
        handler.handle(envelope(listed()))
        handler.handle(envelope(d[0])) shouldBe HandleOutcome.IGNORED
        state(d[0], "MDM-002") shouldBe ItemState.OPEN
    }

    @Test
    fun `INV-AC-21062 a duplicate entry in docUuids is handled once`() {
        seedAll()
        handler.handle(envelope("${d[0]},${d[0]}")) shouldBe HandleOutcome.APPLIED
        state(d[0], "MDM-001") shouldBe ItemState.RESOLVED
        store.writes shouldBe 8 + 1
    }

    @Test
    fun `INV-AC-21062 a contract violation writes nothing`() {
        seedAll()
        val before = store.rows.toMap()
        val writes = store.writes
        shouldThrow<ContractViolation> { handler.handle(envelope("bad")) }
        store.rows shouldBe before
        store.writes shouldBe writes
    }

    @Test
    fun `INV-AC-21062 the hold code set is exactly MDM-001`() {
        MASTER_DATA_HOLD_CODES shouldBe setOf("MDM-001")
    }
}
