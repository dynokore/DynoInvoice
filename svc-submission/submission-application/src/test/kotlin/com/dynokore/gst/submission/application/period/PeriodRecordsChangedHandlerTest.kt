package com.dynokore.gst.submission.application.period

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
import java.time.LocalDate
import java.util.UUID

class PeriodRecordsChangedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000020"))

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Store(
        private val failOnReplace: Boolean = false,
    ) : PeriodRecordStore {
        val replaced = mutableListOf<List<Any>>()

        override fun highestVersion(
            tenant: TenantId,
            entityRef: String,
        ): Int? = if (entityRef == "E-001") 4 else null

        override fun replace(
            tenant: TenantId,
            entityRef: String,
            version: Int,
            periods: List<PeriodRecord>,
        ) {
            replaced += listOf(tenant, entityRef, version, periods)
            if (failOnReplace) error("store down")
        }
    }

    private fun d(s: String) = LocalDate.parse(s)

    private val p1 = PeriodRecord("P1", d("2026-01-01"), d("2026-03-31"))
    private val p2 = PeriodRecord("P2", d("2026-04-01"), d("2026-06-30"))

    private fun envelope(entity: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1",
            subject = entity,
            time = Instant.parse("2026-11-12T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef(entity),
            docUuid = DocUuid(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")),
            transactionDate = d("2026-03-20"),
            docSequence = 1,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    private fun run(
        entity: String,
        version: Int,
        periods: List<PeriodRecord>,
        store: Store = Store(),
    ): Pair<Store, HandleOutcome> {
        val handler =
            PeriodRecordsChangedHandler(tenants, { PeriodRecordsChangedData(entity, version, periods) }, store)
        return store to handler.handle(envelope(entity))
    }

    @Test
    fun `INV-AC-20675 a higher version replaces the periods in the event order`() {
        val (store, outcome) = run("E-001", 5, listOf(p1, p2))
        outcome shouldBe HandleOutcome.APPLIED
        store.replaced.single() shouldBe listOf(tenant, "E-001", 5, listOf(p1, p2))
    }

    @Test
    fun `INV-AC-20675 an equal or lower version is ignored without a replace`() {
        for (v in listOf(4, 3)) {
            val (store, outcome) = run("E-001", v, listOf(p1, p2))
            outcome shouldBe HandleOutcome.IGNORED
            store.replaced shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-20675 a new entity applies at version 1 with a one-day period`() {
        val one = PeriodRecord("P7", d("2026-07-01"), d("2026-07-01"))
        val (store, outcome) = run("E-002", 1, listOf(one))
        outcome shouldBe HandleOutcome.APPLIED
        store.replaced.single() shouldBe listOf(tenant, "E-002", 1, listOf(one))
    }

    @Test
    fun `INV-AC-20675 a malformed period set is a contract violation and writes nothing`() {
        val reversed = PeriodRecord("P1", d("2026-03-31"), d("2026-03-30"))
        val twin = p2.copy(periodRef = "P1")
        for (periods in listOf(listOf(reversed), listOf(p1, twin), emptyList())) {
            val store = Store()
            shouldThrow<ContractViolation> { run("E-001", 6, periods, store) }
            store.replaced shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-20675 a store failure reaches the caller as the same exception`() {
        val e = shouldThrow<IllegalStateException> { run("E-001", 5, listOf(p1, p2), Store(failOnReplace = true)) }
        e.message shouldBe "store down"
    }

    @Test
    fun `any higher version applies, not only the next one`() {
        run("E-001", 6, listOf(p1)).second shouldBe HandleOutcome.APPLIED
    }
}
