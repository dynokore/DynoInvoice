package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-AC-9273 (INV-T-9276, INV-REQ-9271): `setPolicy` of VP-82 posted to the mounted PolicyCommandServlet. The pinned
 * catalogue still lists VP-82 as `planned`; the tests that set it answer the catalogue through [AvailableVp82].
 */
class ConflictingPairsSettingTest {
    private val pairDetail = "these two roles cannot be held by the same person in this tenant"

    private fun run(block: (PairsEnv) -> Unit) = PairsEnv.run(block = block)

    @Test
    fun `INV-AC-9273 tenant admin sets CONFLICTING_PAIRS with a list and a reason and no acknowledgement`() =
        run { env ->
            val response = env.setPairs("tax.operator" to "tax.approver", reason = "separation of duty")
            assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
            val body = env.mapper.readTree(response.contentAsString)
            assertThat(body.get("reason").asString()).isEqualTo("separation of duty")
            assertThat(env.storedRows()).isEqualTo(1)
            env.seed("IDN-A", com.dynokore.gst.access.domain.role.Role.TAX_OPERATOR)
            env.assertRefused(env.refusal { env.grant("IDN-A", "tax.approver") }, pairDetail)
        }

    @Test
    fun `INV-AC-9273 an actor with only security officer is refused and the stored value is unchanged`() =
        run { env ->
            env.seedSecurityOfficer()
            val refused = env.setPairs("tax.operator" to "tax.approver", token = "token-s")
            assertThat(refused.status).isEqualTo(403)
            assertThat(refused.contentAsString).contains("POL-004")
            assertThat(env.storedRows()).isZero()
            env.seed("IDN-A", com.dynokore.gst.access.domain.role.Role.TAX_OPERATOR)
            env.granted("IDN-A", "tax.approver")
        }

    @Test
    fun `INV-AC-9273 the audit entry holds the prior value and the reason`() =
        run { env ->
            val first = env.store("tax.operator" to "tax.approver")
            assertThat(first.get("prior").isNull).isTrue()
            val firstAudit = env.auditData(first.get("auditEventId").asString()).single()
            assertDefaultPrior82(firstAudit)
            val second = env.setPairs("auditor" to "finance.viewer", reason = "second reason")
            assertThat(second.status).describedAs(second.contentAsString).isBetween(200, 299)
            val body = env.mapper.readTree(second.contentAsString)
            assertThat(
                body
                    .get("prior")
                    .get("selected")
                    .get(0)
                    .get("option")
                    .asString(),
            ).isEqualTo("CONFLICTING_PAIRS")
            val stored = env.auditData(body.get("auditEventId").asString()).single()
            assertThat(stored.get("reason").asString()).isEqualTo("second reason")
            assertThat(
                stored
                    .get("priorState")
                    .get("value")
                    .get(0)
                    .get("key")
                    .asString(),
            ).isEqualTo("CONFLICTING_PAIRS")
            // probe: a differing id finds nothing, and the first entry carries the first reason
            assertThat(env.auditData(body.get("auditEventId").asString().dropLast(1) + "x")).isEmpty()
            assertThat(firstAudit.get("reason").asString()).isNotEqualTo(stored.get("reason").asString())
        }

    private fun assertInvalidRefused(
        selected: String,
        code: String,
        other: String,
    ) = run { env ->
        val response =
            env.post(
                "/internal/v1/policies/VP-82",
                "token-a",
                """{"scopeLevel":"TENANT","scopeRef":"${env.tenant}","selected":$selected,""" +
                    """"effectiveDate":"${env.today()}","reason":"invalid value"}""",
            )
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(422)
        val codes = Regex("[A-Z]+-[0-9]+").findAll(response.contentAsString).map { it.value }.toSet()
        assertThat(codes).contains(code).doesNotContain(other)
        assertThat(env.storedRows()).isZero()
    }

    private fun conflicting(value: String) = """[{"option":"CONFLICTING_PAIRS","value":$value}]"""

    @Test
    fun `INV-AC-9273 an empty list is refused 422 POL-013 by the structural check first`() =
        assertInvalidRefused(conflicting("[]"), "POL-013", "POL-014")

    @Test
    fun `INV-AC-9273 NO_EXTRA_PAIRS with a value is refused 422 POL-013 by the structural check first`() =
        assertInvalidRefused("""[{"option":"NO_EXTRA_PAIRS","value":[]}]""", "POL-013", "POL-014")

    @Test
    fun `INV-AC-9273 equal roles is refused 422 POL-014 by the owning service check`() =
        assertInvalidRefused(conflicting("""[{"first":"auditor","second":"auditor"}]"""), "POL-014", "POL-013")

    @Test
    fun `INV-AC-9273 a role name that is not a wire name is refused 422 POL-014 by the owning service check`() =
        assertInvalidRefused(conflicting("""[{"first":"Tenant.Admin","second":"auditor"}]"""), "POL-014", "POL-013")

    @Test
    fun `INV-AC-9273 a value without the double is refused while the pinned row is planned`() {
        val planned =
            com.dynokore.platform.productruntime.catalogue
                .GstVariationPointCatalogue("access-svc")
                .find(
                    com.dynokore.platform.configresolver
                        .VariationPointKey("VP-82"),
                )!!
                .status
        if (planned == com.dynokore.platform.configresolver.VpStatus.PLANNED) {
            PairsEnv.run(withDouble = false) { env ->
                val refused = env.setPairs("tax.operator" to "tax.approver")
                assertThat(refused.status).isGreaterThanOrEqualTo(400)
                assertThat(env.storedRows()).isZero()
            }
        }
    }
}

private fun assertDefaultPrior82(audit: tools.jackson.databind.JsonNode) {
    val prior = audit.get("priorState")
    assertThat(prior.isNull).isFalse()
    assertThat(
        prior
            .get("value")
            .get(0)
            .get("key")
            .asString(),
    ).isEqualTo("NO_EXTRA_PAIRS")
    assertThat(prior.get("source").asString()).isEqualTo("DEFAULT")
}
