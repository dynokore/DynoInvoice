package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.domain.CeilingChange
import com.dynokore.gst.transport.domain.CeilingRefusal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID

/**
 * The throughput ceiling of an AP connection at the service boundary over a real database (INV-REQ-1317). The
 * transport contract offers no operation to set it yet, so the use case is driven directly.
 */
class CeilingBoundaryTest {
    private fun Harness.effective(tenant: UUID): BigDecimal = asTenant(tenant) { ceiling.current(primary).effective }

    private fun Harness.set(
        tenant: UUID,
        value: String,
    ): CeilingChange = asTenant(tenant) { ceiling.set(primary, BigDecimal(value)) }

    @Test
    fun `INV-AC-7850 without a tenant value the ceiling is the agreed rate and a lower value is accepted`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        h.connect(tenant, h.primary, "60")
        h.effective(tenant).compareTo(BigDecimal("60")) shouldBe 0
        h.set(tenant, "30").shouldBeInstanceOf<CeilingChange.Applied>()
        h.effective(tenant).compareTo(BigDecimal("30")) shouldBe 0
    }

    @Test
    fun `INV-AC-7850 a tenant value of zero or below is not accepted as a lower value`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        h.connect(tenant, h.primary, "60")
        listOf("0", "-1", "-0.001").forEach {
            h.set(tenant, it).shouldBeInstanceOf<CeilingChange.Refused>().reason shouldBe CeilingRefusal.NOT_POSITIVE
        }
        h.effective(tenant).compareTo(BigDecimal("60")) shouldBe 0
    }

    @Test
    fun `INV-AC-7851 a ceiling above the agreed rate is refused and the agreed rate stays in force`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        h.connect(tenant, h.primary, "60")
        // one unit above the agreed rate (the smallest step of the column) is refused;
        // the agreed rate itself is not above it
        h.set(tenant, "60.001").shouldBeInstanceOf<CeilingChange.Refused>().reason shouldBe
            CeilingRefusal.ABOVE_AGREED_RATE
        h.effective(tenant).compareTo(BigDecimal("60")) shouldBe 0
        h.set(tenant, "60").shouldBeInstanceOf<CeilingChange.Applied>()
        h.effective(tenant).compareTo(BigDecimal("60")) shouldBe 0
    }

    @Test
    fun `INV-AC-2305 a higher ceiling is refused as above the agreed rate and the agreed rate stays`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        h.connect(tenant, h.primary, "60")
        h.set(tenant, "30").shouldBeInstanceOf<CeilingChange.Applied>()
        h.set(tenant, "120").shouldBeInstanceOf<CeilingChange.Refused>().reason shouldBe
            CeilingRefusal.ABOVE_AGREED_RATE
        // the refused value changed nothing: the lower tenant value of before is still in force
        h.effective(tenant).compareTo(BigDecimal("30")) shouldBe 0
    }

    @Test
    fun `a tenant ceiling above the agreed rate is refused by the database as well`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        h.connect(tenant, h.primary, "60")
        shouldThrow<DataAccessException> {
            h.asTenant(tenant) {
                h.dsl
                    .update(AP_CONNECTION)
                    .set(AP_CONNECTION.TENANT_CEILING, BigDecimal("61"))
                    .where(AP_CONNECTION.ADAPTER_ID.eq(h.primary.value))
                    .execute()
            }
        }
    }
}
