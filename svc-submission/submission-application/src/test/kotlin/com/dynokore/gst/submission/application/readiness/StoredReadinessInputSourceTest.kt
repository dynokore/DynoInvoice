package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.completeness.StoredRow
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.gst.submission.application.startdate.ObligationStartDates
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The production readiness input source over in-memory ports (INV-AC-22208). */
class StoredReadinessInputSourceTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000082"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000083"))
    private val start = LocalDate.of(2026, 4, 1)

    private class Tenants(
        var tenant: TenantId,
    ) : TenantContext {
        override fun current(): TenantId = tenant

        override fun currentActor(): String? = null

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }

    private class StartDates : ObligationStartDates {
        val dates = mutableMapOf<Pair<TenantId, String>, LocalDate>()

        override fun record(
            tenant: TenantId,
            entityRef: String,
            startDate: LocalDate,
            effectiveDate: LocalDate,
        ): Boolean {
            dates[tenant to entityRef] = startDate
            return true
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
        ): LocalDate? = dates[tenant to entityRef]
    }

    private class Rows : CompletenessRows {
        val stored = mutableMapOf<Triple<TenantId, String, String>, List<StoredRow>>()
        val readTenants = mutableListOf<TenantId>()

        override fun rows(
            tenant: TenantId,
            entityRef: String,
            period: String,
        ): List<StoredRow> {
            readTenants += tenant
            return stored[Triple(tenant, entityRef, period)].orEmpty()
        }
    }

    private val tenants = Tenants(tenant)
    private val dates = StartDates()
    private val rows = Rows()
    private val source = StoredReadinessInputSource(tenants, dates, rows)

    private fun row(
        id: String,
        state: StoredState,
    ) = StoredRow(id, state)

    @Test
    fun `INV-AC-22208 no start-date row is Unavailable with a null date even when the rows would pass`() {
        rows.stored[Triple(tenant, "E", "P")] = listOf(row("feed:F1/COUNT", StoredState.NOT_FAILING))
        source.inputs("E", "P") shouldBe ReadinessInputs(null, CompletenessState.Unavailable)
    }

    @Test
    fun `INV-AC-22208 a start date and no completeness row is Unavailable`() {
        dates.record(tenant, "E", start, start)
        source.inputs("E", "P") shouldBe ReadinessInputs(start, CompletenessState.Unavailable)
    }

    @Test
    fun `INV-AC-22208 a start date and one not-failing feed row is Passed`() {
        dates.record(tenant, "E", start, start)
        rows.stored[Triple(tenant, "E", "P")] = listOf(row("feed:F1/COUNT", StoredState.NOT_FAILING))
        source.inputs("E", "P") shouldBe ReadinessInputs(start, CompletenessState.Passed)
    }

    @Test
    fun `INV-AC-22208 a start date and one failing feed row is Failed with its identifier`() {
        dates.record(tenant, "E", start, start)
        rows.stored[Triple(tenant, "E", "P")] = listOf(row("feed:F1/COUNT", StoredState.FAILING))
        source.inputs("E", "P") shouldBe ReadinessInputs(start, CompletenessState.Failed(listOf("feed:F1/COUNT")))
    }

    @Test
    fun `INV-AC-22208 a start date and one open window part is Unavailable`() {
        dates.record(tenant, "E", start, start)
        rows.stored[Triple(tenant, "E", "P")] = listOf(row("window:P", StoredState.OPEN))
        source.inputs("E", "P") shouldBe ReadinessInputs(start, CompletenessState.Unavailable)
    }

    @Test
    fun `INV-AC-22208 the start date of E is not returned for E2`() {
        dates.record(tenant, "E", start, start)
        dates.record(tenant, "E2", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1))
        rows.stored[Triple(tenant, "E", "P")] = listOf(row("feed:F1/COUNT", StoredState.NOT_FAILING))
        source.inputs("E2", "P") shouldBe
            ReadinessInputs(LocalDate.of(2026, 5, 1), CompletenessState.Unavailable)
    }

    @Test
    fun `INV-AC-22208 no row of the other tenant is read`() {
        dates.record(tenant, "E", start, start)
        dates.record(other, "E", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1))
        rows.stored[Triple(tenant, "E", "P")] = listOf(row("feed:F1/COUNT", StoredState.NOT_FAILING))
        rows.stored[Triple(other, "E", "P")] = listOf(row("feed:F1/COUNT", StoredState.FAILING))
        tenants.tenant = other
        source.inputs("E", "P") shouldBe
            ReadinessInputs(LocalDate.of(2026, 1, 1), CompletenessState.Failed(listOf("feed:F1/COUNT")))
        rows.readTenants shouldBe listOf(other)
    }
}
