package com.dynokore.gst.consoleBff.application.duedates

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** In-memory implementation of the port: the conformance fixture (compile-time, no reflection on names). */
class PeriodDueDateStoreConformance : PeriodDueDateStore {
    val rows = linkedMapOf<Pair<String, PeriodKey>, Pair<LocalDate, Long>>()

    override fun applyIfNewer(
        tenantId: String,
        key: PeriodKey,
        dueDate: LocalDate,
        version: Long,
    ): Boolean {
        val held = rows[tenantId to key]
        if (held != null && held.second >= version) return false
        rows[tenantId to key] = dueDate to version
        return true
    }

    override fun putInitialIfAbsent(
        tenantId: String,
        key: PeriodKey,
        dueDate: LocalDate,
    ): Boolean = rows.putIfAbsent(tenantId to key, dueDate to 0L) == null

    override fun dueDates(
        tenantId: String,
        keys: Set<PeriodKey>,
    ): Map<PeriodKey, LocalDate> = keys.mapNotNull { k -> rows[tenantId to k]?.let { k to it.first } }.toMap()
}

class PeriodDueDateHandlersTest {
    private val tenantId = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val tenant = tenantId.value.toString()
    private val store = PeriodDueDateStoreConformance()
    private val p1 = PeriodKey("E1", "P1")
    private val p2 = PeriodKey("E1", "P2")

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

    /** "E1|P1|2026-12-15|2" (version "-" is missing); reads like the contract: every fact is required. */
    private val changeReader =
        object : PeriodDueDateChangedReader {
            override fun read(dataJson: String): DueDateChange {
                val p = dataJson.split('|')
                val version = p.getOrNull(3)?.toLongOrNull()
                val refsOk = p.size >= 4 && p[0].isNotBlank() && p[1].isNotBlank()
                if (!refsOk || (version ?: 0L) < 1) throw ContractViolation("invalid change event")
                val date = parse(p[2])
                return DueDateChange(PeriodKey(p[0], p[1]), date, version!!)
            }
        }

    /** "E1|P1=2026-11-30,P2=2027-01-15,P3=" (an empty value is an absent filingDueDate). */
    private val recordsReader =
        object : PeriodRecordsChangedReader {
            override fun read(dataJson: String): InitialDueDates {
                if (!dataJson.contains('|')) throw ContractViolation("not an object")
                val entity = dataJson.substringBefore('|')
                val periods =
                    dataJson.substringAfter('|').split(',').associate {
                        it.substringBefore('=') to
                            it.substringAfter('=')
                    }
                return InitialDueDates(entity, periods.filterValues { it.isNotEmpty() }.mapValues { parse(it.value) })
            }
        }

    private fun parse(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("not a calendar date: $text")
        }

    private val change = PeriodDueDateChangedHandler(tenants, changeReader, store)
    private val records = PeriodRecordsChangedHandler(tenants, recordsReader, store)

    private fun envelope(data: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.period.v1",
            subject = "E1",
            time = Instant.parse("2026-11-12T08:00:00Z"),
            tenant = tenantId,
            entity = EntityRef("E1"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun change(
        v: String,
        date: String = "2026-12-15",
    ) = change.handle(envelope("E1|P1|$date|$v"))

    private val recordsData = "E1|P1=2026-11-30,P2=2027-01-15,P3="

    @Test
    fun `INV-AC-21076 change events apply in version order`() {
        change("2") shouldBe HandleOutcome.APPLIED
        store.rows[tenant to p1] shouldBe (LocalDate.parse("2026-12-15") to 2L)
        change("2") shouldBe HandleOutcome.IGNORED
        change("1", "2026-12-01") shouldBe HandleOutcome.IGNORED
        store.rows[tenant to p1] shouldBe (LocalDate.parse("2026-12-15") to 2L)
        change("3", "2026-12-20") shouldBe HandleOutcome.APPLIED
        store.rows[tenant to p1] shouldBe (LocalDate.parse("2026-12-20") to 3L)
    }

    @Test
    fun `INV-AC-21076 records on an empty store write version 0 for dated periods only and repeat is ignored`() {
        records.handle(envelope(recordsData)) shouldBe HandleOutcome.APPLIED
        store.rows[tenant to p1] shouldBe (LocalDate.parse("2026-11-30") to 0L)
        store.rows[tenant to p2] shouldBe (LocalDate.parse("2027-01-15") to 0L)
        store.rows.keys.contains(tenant to PeriodKey("E1", "P3")) shouldBe false
        records.handle(envelope(recordsData)) shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-21076 records never overwrite a version-ordered value and still store the missing period`() {
        change("2")
        records.handle(envelope(recordsData)) shouldBe HandleOutcome.APPLIED
        store.rows[tenant to p1] shouldBe (LocalDate.parse("2026-12-15") to 2L)
        store.rows[tenant to p2] shouldBe (LocalDate.parse("2027-01-15") to 0L)
    }

    @Test
    fun `INV-AC-21076 a version 1 change supersedes the initial version 0`() {
        records.handle(envelope(recordsData))
        change("1", "2026-12-01") shouldBe HandleOutcome.APPLIED
        store.dueDates(tenant, setOf(p1, p2)) shouldBe
            mapOf(p1 to LocalDate.parse("2026-12-01"), p2 to LocalDate.parse("2027-01-15"))
    }

    @Test
    fun `INV-AC-21076 change events fail closed and write nothing`() {
        listOf(
            "E1|P1|2026-12-15|-",
            "E1|P1|2026-12-15|0",
            "E1|P1|2026-02-30|2",
            "E1|P1|tomorrow|2",
            " |P1|2026-12-15|2",
            "E1| |2026-12-15|2",
            "not json",
        ).forEach { shouldThrow<ContractViolation> { change.handle(envelope(it)) } }
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-21076 records with a non-date filingDueDate or a non-object fail closed and write nothing`() {
        shouldThrow<ContractViolation> { records.handle(envelope("E1|P1=2026-11-30,P2=nope")) }
        shouldThrow<ContractViolation> { records.handle(envelope("[]")) }
        store.rows.isEmpty() shouldBe true
    }
}
