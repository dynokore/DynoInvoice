package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.PackAlertDraft
import com.dynokore.gst.notification.domain.alerting.PolicyTier
import com.dynokore.gst.notification.domain.alerting.TENANT_WIDE_ENTITY_REF
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

private class StoreDown : RuntimeException("store down")

class FanOutPackCurrencyChangedTest {
    private fun id(n: Int) = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000$n"))

    private val t1 = id(1)
    private val t2 = id(2)
    private val t3 = id(3)

    private var list: RegisteredTenantList = RegisteredTenantList.Complete(setOf(t3, t1, t2))
    private var sourceCalls = 0
    private val events = mutableListOf<String>()
    private val inside = mutableListOf<TenantId>()
    private var current: TenantId? = null
    private val raised = mutableListOf<Pair<TenantId, PackAlertDraft>>()
    private val open = mutableSetOf(t1, t2)
    private var failStoreFor: TenantId? = null

    private val policies =
        mapOf(
            t1 to PackPolicy(listOf("u-1"), listOf(PolicyTier(1, 30), PolicyTier(2, 14), PolicyTier(4, null))),
            t2 to PackPolicy(listOf("u-2"), listOf(PolicyTier(1, 30), PolicyTier(3, 14), PolicyTier(4, null))),
        )

    private val fanOut =
        FanOutPackCurrencyChanged(
            object : RegisteredTenantSource {
                override fun registered(): RegisteredTenantList {
                    sourceCalls++
                    events += "source"
                    return list
                }
            },
            object : PerTenantTransaction {
                override fun <T> run(
                    tenant: TenantId,
                    block: () -> T,
                ): T {
                    events += "tx"
                    inside += tenant
                    current = tenant
                    try {
                        return block()
                    } finally {
                        current = null
                    }
                }
            },
            HandlePackCurrencyChanged(
                object : PackPolicySource {
                    override fun policy(tenant: TenantId): PackPolicy? {
                        check(current == tenant) { "policy read outside the per-tenant transaction" }
                        events += "policy"
                        return policies[tenant]
                    }
                },
                object : PackAlertStore {
                    override fun raise(
                        tenant: TenantId,
                        draft: PackAlertDraft,
                    ): Boolean {
                        if (tenant == failStoreFor) throw StoreDown()
                        raised += tenant to draft
                        return true
                    }

                    override fun resolveOpen(tenant: TenantId): Boolean = open.remove(tenant)
                },
            ),
        )

    private fun payload(
        dep: Any? = "DEP-001",
        status: Any? = "RAISED",
        days: Any? = 14,
    ) = mapOf("depCode" to dep, "status" to status, "daysToEffectiveDate" to days)

    @Test
    fun `INV-AC-22424 RAISED alerts each tenant with its own policy and reports the tenant without one`() {
        val e = assertThrows<PackAlertNotDelivered> { fanOut.handle(payload()) }
        inside shouldBe listOf(t1, t2, t3)
        raised shouldBe
            listOf(
                t1 to PackAlertDraft(AlertClass.PACK_STALENESS, "PACK_NOT_ACTIVATED", 2, TENANT_WIDE_ENTITY_REF),
                t2 to PackAlertDraft(AlertClass.PACK_STALENESS, "PACK_NOT_ACTIVATED", 3, TENANT_WIDE_ENTITY_REF),
            )
        val message = e.message.orEmpty()
        message.contains(t3.value.toString()) shouldBe true
        message.contains(t1.value.toString()) shouldBe false
        message.contains(t2.value.toString()) shouldBe false
    }

    @Test
    fun `INV-AC-22424 CLEARED resolves the open alerts and counts nothing open for a tenant with none`() {
        val result = fanOut.handle(payload(status = "CLEARED", days = null))
        result shouldBe FanOutResult(resolved = 2, nothingOpen = 1, tenantsVisited = 3)
        result.applied shouldBe true
        open shouldBe emptySet()
    }

    @Test
    fun `INV-AC-22424 an incomplete list throws and touches nothing else`() {
        list = RegisteredTenantList.Incomplete
        assertThrows<PackAlertNotDelivered> { fanOut.handle(payload()) }
        events shouldBe listOf("source")
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22424 DEP-002 is ignored and the source is not called`() {
        fanOut.handle(payload(dep = "DEP-002")) shouldBe FanOutResult(ignored = true)
        sourceCalls shouldBe 0
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22424 an invalid payload is a contract violation before any port is used`() {
        assertThrows<ContractViolation> { fanOut.handle(payload(status = "RAISE")) }
        assertThrows<ContractViolation> { fanOut.handle(payload(dep = null)) }
        sourceCalls shouldBe 0
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22424 a complete empty list throws nothing and is not applied`() {
        list = RegisteredTenantList.Complete(emptySet())
        val result = fanOut.handle(payload())
        result shouldBe FanOutResult()
        result.applied shouldBe false
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22424 an unexpected store failure propagates at once and later tenants are not visited`() {
        failStoreFor = t2
        assertThrows<StoreDown> { fanOut.handle(payload()) }
        inside shouldBe listOf(t1, t2)
    }
}
