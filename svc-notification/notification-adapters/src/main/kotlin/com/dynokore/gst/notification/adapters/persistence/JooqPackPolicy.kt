package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.AlertPolicy.Companion.ALERT_POLICY
import com.dynokore.gst.notification.application.alerting.PackPolicy
import com.dynokore.gst.notification.application.alerting.PackPolicySource
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.PolicyTier
import com.dynokore.gst.notification.domain.alerting.TENANT_WIDE_ENTITY_REF
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Reads the PACK_STALENESS policy at the tenant-wide reference from notification.alert_policy (INV-REQ-1080,
 * INV-Q-20864 A). Forced row level security limits the read to the bound tenant, so the tenant is not a query
 * argument. No row is null; nothing is defaulted.
 */
@Component
class JooqPackPolicy(
    private val dsl: DSLContext,
) : PackPolicySource {
    override fun policy(tenant: TenantId): PackPolicy? =
        dsl
            .select(ALERT_POLICY.RECIPIENTS, ALERT_POLICY.ESCALATION_TIERS)
            .from(ALERT_POLICY)
            .where(ALERT_POLICY.ENTITY_REF.eq(TENANT_WIDE_ENTITY_REF))
            .and(ALERT_POLICY.ALERT_CLASS.eq(AlertClass.PACK_STALENESS.name))
            .fetchOne()
            ?.let { PackPolicy(recipients(it.value1()?.data()), tiers(it.value2()?.data())) }

    private fun recipients(json: String?): List<String> = elements(json).filterIsInstance<String>()

    private fun tiers(json: String?): List<PolicyTier> =
        elements(json).filterIsInstance<Map<*, *>>().map {
            PolicyTier((it["tier"] as Number).toInt(), (it["daysToDue"] as? Number)?.toInt())
        }

    // A JSON document is a YAML flow document; the safe constructor builds plain lists, maps, strings and numbers only.
    private fun elements(json: String?): List<Any?> =
        json?.let { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(it) as? List<*> } ?: emptyList()
}
