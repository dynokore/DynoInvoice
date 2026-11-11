package com.dynokore.gst.submission.application.register

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The deadline source over the stored period due dates (INV-AC-20513). */
class PeriodDueDateDeadlineSourceTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000021"))

    /** Compile-time conformance fixture: one object implements the three ports, called through the port types. */
    private object Fixture : DocumentKindResolver, FilingDeadlineSource, PeriodDueDates {
        val rows = mutableMapOf<Triple<TenantId, String, String>, LocalDate>()

        override fun kindOf(data: CanonicalVersionCreatedData) = null

        override fun inputs(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ) = find(tenant, entityRef, periodRef)?.let { FilingDeadlineInputs(it, emptyList()) }

        override fun upsert(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
            dueDate: LocalDate,
            sequence: Long,
        ): UpsertResult {
            rows[Triple(tenant, entityRef, periodRef)] = dueDate
            return UpsertResult.INSERTED
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): LocalDate? = rows[Triple(tenant, entityRef, periodRef)]
    }

    private fun data() = CanonicalVersionCreatedData("d", 1, LocalDate.of(2026, 3, 20), "P", "SUBMITTABLE", emptyMap())

    private val dates: PeriodDueDates = Fixture
    private val source = PeriodDueDateDeadlineSource(dates)

    init {
        dates.upsert(tenant, "E", "P", LocalDate.of(2026, 7, 31), 1)
    }

    @Test
    fun `INV-AC-20513 a stored row gives the date and an empty filedOn`() {
        source.inputs(tenant, "E", "P") shouldBe FilingDeadlineInputs(LocalDate.of(2026, 7, 31), emptyList())
    }

    @Test
    fun `INV-AC-20513 no row gives null`() {
        source.inputs(tenant, "E2", "P") shouldBe null
    }

    @Test
    fun `INV-AC-20513 a row of another period gives null`() {
        source.inputs(tenant, "E", "P2") shouldBe null
        source.inputs(other, "E", "P") shouldBe null
    }

    @Test
    fun `INV-AC-20513 the fixture is usable through the other two port types`() {
        val kinds: DocumentKindResolver = Fixture
        val deadlines: FilingDeadlineSource = Fixture
        deadlines.inputs(tenant, "E", "P")?.filingDueDate shouldBe LocalDate.of(2026, 7, 31)
        kinds.kindOf(data()) shouldBe null
    }
}
