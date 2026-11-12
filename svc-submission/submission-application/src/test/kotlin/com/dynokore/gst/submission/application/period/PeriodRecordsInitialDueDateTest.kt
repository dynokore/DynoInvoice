package com.dynokore.gst.submission.application.period

import com.dynokore.platform.outboxandqueue.CausationId
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

class PeriodRecordsInitialDueDateTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000021"))

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

    private fun d(s: String) = LocalDate.parse(s)

    private data class Row(
        val date: LocalDate,
        val version: Int,
    )

    private class Fakes(
        val failPort: Boolean = false,
    ) {
        val log = mutableListOf<String>()
        val initCalls = mutableListOf<Triple<String, String, LocalDate>>()
        val rows =
            mutableMapOf(
                "2026-07" to Row(LocalDate.parse("2026-08-20"), 3),
            )
        var highest: Int? = null

        val store =
            object : PeriodRecordStore {
                override fun highestVersion(
                    tenant: TenantId,
                    entityRef: String,
                ): Int? = highest

                override fun replace(
                    tenant: TenantId,
                    entityRef: String,
                    version: Int,
                    periods: List<PeriodRecord>,
                ) {
                    log += "replace($entityRef, version $version)"
                    highest = version
                }
            }

        val port =
            InitialFilingDueDates { _, entityRef, periodRef, dueDate ->
                log += "initialise($entityRef, $periodRef, $dueDate)"
                initCalls += Triple(entityRef, periodRef, dueDate)
                if (failPort) error("port down")
                if (periodRef !in rows) rows[periodRef] = Row(dueDate, 0)
            }
    }

    private val v5 =
        PeriodRecordsChangedData(
            "E1",
            5,
            listOf(
                PeriodRecord("2026-06", d("2026-06-01"), d("2026-06-30"), d("2026-07-20")),
                PeriodRecord("2026-07", d("2026-07-01"), d("2026-07-31"), d("2026-08-25")),
                PeriodRecord("2026-08", d("2026-08-01"), d("2026-08-31")),
            ),
        )
    private val v4 =
        PeriodRecordsChangedData(
            "E1",
            4,
            listOf(PeriodRecord("2026-06", d("2026-06-01"), d("2026-06-30"), d("2026-07-22"))),
        )

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1",
            subject = "E1",
            time = Instant.parse("2026-11-12T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E1"),
            docUuid = DocUuid(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")),
            transactionDate = d("2026-03-20"),
            docSequence = 1,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    private fun handle(
        f: Fakes,
        data: PeriodRecordsChangedData,
    ): HandleOutcome = PeriodRecordsChangedHandler(tenants, { data }, f.store, f.port).handle(envelope())

    @Test
    fun `INV-AC-20794 V5 initialises the periods without a row and leaves the stored one`() {
        val f = Fakes()
        handle(f, v5) shouldBe HandleOutcome.APPLIED
        f.initCalls shouldBe
            listOf(
                Triple("E1", "2026-06", d("2026-07-20")),
                Triple("E1", "2026-07", d("2026-08-25")),
            )
        f.rows["2026-06"] shouldBe Row(d("2026-07-20"), 0)
        f.rows["2026-07"] shouldBe Row(d("2026-08-20"), 3)
        f.rows.containsKey("2026-08") shouldBe false
    }

    @Test
    fun `INV-AC-20794 V5b redelivery is ignored and the port gets no call`() {
        val f = Fakes()
        handle(f, v5)
        val before = f.initCalls.size
        handle(f, v5) shouldBe HandleOutcome.IGNORED
        f.initCalls.size shouldBe before
    }

    @Test
    fun `INV-AC-20794 V4 older event is ignored and changes nothing`() {
        val f = Fakes()
        handle(f, v5)
        val before = f.initCalls.size
        handle(f, v4) shouldBe HandleOutcome.IGNORED
        f.initCalls.size shouldBe before
        f.rows["2026-06"] shouldBe Row(d("2026-07-20"), 0)
    }

    @Test
    fun `INV-AC-20794 failing port propagates after replace and stops the loop`() {
        val f = Fakes(failPort = true)
        shouldThrow<IllegalStateException> { handle(f, v5) }
        f.log shouldBe listOf("replace(E1, version 5)", "initialise(E1, 2026-06, 2026-07-20)")
    }
}
