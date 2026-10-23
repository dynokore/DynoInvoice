package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

/**
 * The stored `access.outbox` data of a `setPolicy` call: actor, action, tenant scope, reason and prior state of VP-81,
 * and the new value of the answer of the same call (the stored data holds no new-value field; the answer's
 * `auditEventId` is the key of the stored row).
 */
internal fun assertStoredAuditFields(
    stored: JsonNode,
    tenant: TenantId,
    reason: String,
    newOption: String,
    answer: JsonNode,
) {
    assertThat(stored.get("actor").asString()).isEqualTo("user:tenant-admin")
    assertThat(stored.get("action").asString()).isEqualTo("setPolicy")
    assertThat(stored.get("reason").asString()).isEqualTo(reason)
    assertThat(stored.get("scope").asString()).isEqualTo("TENANT:${tenant.value}")
    assertThat(stored.get("priorState").get("vpId").asString()).isEqualTo("VP-81")
    assertThat(stored.get("priorState").get("scope").asString()).isEqualTo("TENANT:${tenant.value}")
    assertThat(
        answer
            .get("selected")
            .get(0)
            .get("option")
            .asString(),
    ).isEqualTo(newOption)
}

/** The writer resolves the prior value at the effective date: with nothing set it is the default. */
internal fun assertDefaultPrior(audit: JsonNode) {
    val firstPrior = audit.get("priorState")
    assertThat(firstPrior.isNull).isFalse()
    assertThat(
        firstPrior
            .get("value")
            .get(0)
            .get("key")
            .asString(),
    ).isEqualTo("ONE_HOLDER_WITHDRAW_BLOCKED")
    assertThat(firstPrior.get("source").asString()).isEqualTo("DEFAULT")
}
