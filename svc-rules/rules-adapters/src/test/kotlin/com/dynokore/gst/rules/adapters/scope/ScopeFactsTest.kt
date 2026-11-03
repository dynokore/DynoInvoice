package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The two profile dates the submission window reads, from `EntityProfileChanged` events (INV-REQ-376..378). */
class ScopeFactsTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val otherTenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2027, 1, 1)

    private fun replicaWith(
        entity: String,
        tenantId: TenantId,
        vararg entries: Pair<String, String>,
        effective: LocalDate = day,
    ): ProfileScopeFacts {
        val replica = StructureReplica(clock)
        replica.accept(
            EntityProfileChanged(
                tenantId,
                entity,
                StructureNode(
                    StructureNodeKind.ENTITY,
                    null,
                    false,
                    "E",
                    "E1",
                    entries.map { ProfileEntry(it.first, it.second, effective) },
                    1,
                ),
            ),
        )
        return ProfileScopeFacts(replica, clock)
    }

    private fun on(date: LocalDate) = TransactionDate(date)

    @Test
    fun `an obligationStartDate entry gives the member on and after its effective date, null before`() {
        val facts = replicaWith("E1", tenant, "obligationStartDate" to "2027-01-01")
        facts.datesOf(tenant, "E1", on(day)).obligationStartDate shouldBe on(day)
        facts.datesOf(tenant, "E1", on(day.plusDays(40))).obligationStartDate shouldBe on(day)
        facts.datesOf(tenant, "E1", on(day.minusDays(1))).obligationStartDate shouldBe null
    }

    @Test
    fun `no entry gives null for both members`() {
        replicaWith("E1", tenant).datesOf(tenant, "E1", on(day)) shouldBe ProfileDates(null, null)
    }

    @Test
    fun `a registrationEndDate entry gives the member`() {
        val facts = replicaWith("E1", tenant, "registrationEndDate" to "2027-06-30")
        facts.datesOf(tenant, "E1", on(day)) shouldBe ProfileDates(null, on(LocalDate.of(2027, 6, 30)))
    }

    @Test
    fun `look-alike attribute names give null for both members`() {
        listOf("registrationEnd", "deregistrationDate", "implementationDate", "registrationStartDate", "ObligationStartDate")
            .forEach { name ->
                replicaWith("E1", tenant, name to "2027-01-01").datesOf(tenant, "E1", on(day)) shouldBe
                    ProfileDates(null, null)
            }
    }

    @Test
    fun `an entry of another entity or tenant gives null`() {
        val facts = replicaWith("E1", tenant, "obligationStartDate" to "2027-01-01")
        facts.datesOf(tenant, "E2", on(day)) shouldBe ProfileDates(null, null)
        facts.datesOf(otherTenant, "E1", on(day)) shouldBe ProfileDates(null, null)
    }

    @Test
    fun `a value that is not an ISO date gives null`() {
        replicaWith("E1", tenant, "obligationStartDate" to "01/01/2027").datesOf(tenant, "E1", on(day)) shouldBe
            ProfileDates(null, null)
    }
}
