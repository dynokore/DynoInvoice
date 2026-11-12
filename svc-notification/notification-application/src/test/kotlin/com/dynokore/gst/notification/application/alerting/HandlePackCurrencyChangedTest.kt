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

class HandlePackCurrencyChangedTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val tiers = listOf(PolicyTier(1, 30), PolicyTier(2, 14), PolicyTier(3, 7), PolicyTier(4, null))
    private var policy: PackPolicy? = PackPolicy(listOf("u-1", "u-2"), tiers)
    private var openAlert = false
    private val reads = mutableListOf<TenantId>()
    private val raised = mutableListOf<PackAlertDraft>()
    private var resolves = 0

    private val handler =
        HandlePackCurrencyChanged(
            object : PackPolicySource {
                override fun policy(tenant: TenantId): PackPolicy? {
                    reads += tenant
                    return policy
                }
            },
            object : PackAlertStore {
                override fun raise(
                    tenant: TenantId,
                    draft: PackAlertDraft,
                ): Boolean {
                    raised += draft
                    return !openAlert.also { openAlert = true }
                }

                override fun resolveOpen(tenant: TenantId): Boolean {
                    resolves++
                    return openAlert.also { openAlert = false }
                }
            },
        )

    private fun payload(
        dep: Any? = "DEP-001",
        status: Any? = "RAISED",
        days: Any? = 14,
    ) = mapOf("depCode" to dep, "status" to status, "daysToEffectiveDate" to days, "packVersion" to "x")

    @Test
    fun `INV-AC-20909 first raise creates one tier 2 draft at the tenant-wide reference`() {
        handler.handle(tenant, payload()) shouldBe HandlePackCurrencyChangedResult.Raised
        raised shouldBe
            listOf(PackAlertDraft(AlertClass.PACK_STALENESS, "PACK_NOT_ACTIVATED", 2, TENANT_WIDE_ENTITY_REF))
    }

    @Test
    fun `INV-AC-20909 a second raise while open is ALREADY_OPEN`() {
        handler.handle(tenant, payload())
        handler.handle(tenant, payload(days = 14L)) shouldBe HandlePackCurrencyChangedResult.AlreadyOpen
    }

    @Test
    fun `INV-AC-20909 CLEARED resolves an open alert and reports NOTHING_OPEN when none`() {
        handler.handle(tenant, payload())
        val cleared = payload(status = "CLEARED", days = null)
        handler.handle(tenant, cleared) shouldBe HandlePackCurrencyChangedResult.Resolved
        handler.handle(tenant, cleared) shouldBe HandlePackCurrencyChangedResult.NothingOpen
        reads shouldBe listOf(tenant)
    }

    @Test
    fun `INV-AC-20909 DEP-002 touches neither port`() {
        val ignored = HandlePackCurrencyChangedResult.Ignored
        handler.handle(tenant, payload(dep = "DEP-002")) shouldBe ignored
        handler.handle(tenant, payload(dep = "DEP-002", status = "CLEARED")) shouldBe ignored
        reads shouldBe emptyList()
        raised shouldBe emptyList()
        resolves shouldBe 0
    }

    @Test
    fun `INV-AC-20909 no policy and no recipient throw PackAlertNotDelivered and raise nothing`() {
        policy = null
        assertThrows<PackAlertNotDelivered> { handler.handle(tenant, payload()) }
        policy = PackPolicy(emptyList(), tiers)
        assertThrows<PackAlertNotDelivered> { handler.handle(tenant, payload()) }
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20909 invalid payloads throw ContractViolation and call no port`() {
        assertThrows<ContractViolation> { handler.handle(tenant, payload(status = "RAISE")) }
        assertThrows<ContractViolation> { handler.handle(tenant, payload(dep = null)) }
        assertThrows<ContractViolation> { handler.handle(tenant, payload(dep = " ")) }
        assertThrows<ContractViolation> { handler.handle(tenant, payload(days = -1)) }
        reads shouldBe emptyList()
        raised shouldBe emptyList()
        resolves shouldBe 0
    }

    @Test
    fun `INV-AC-20909 the policy is read once for the tenant`() {
        handler.handle(tenant, payload())
        reads shouldBe listOf(tenant)
    }
}
