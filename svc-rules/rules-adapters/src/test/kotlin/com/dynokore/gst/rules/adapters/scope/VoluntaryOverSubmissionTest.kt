package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.PolicyChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.PolicySource
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * VP-21 is read from the option list the producer writes, strictly (INV-REQ-375, INV-REQ-1282, INV-AC-25208), over a
 * real [PolicyReplica].
 */
class VoluntaryOverSubmissionTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    private val start = LocalDate.of(2026, 1, 1)
    private val date = TransactionDate(LocalDate.of(2026, 4, 1))
    private val topic = "gst.platform.policy-changed.v1"
    private val on = """[{"key":"ON"}]"""

    private fun replica() =
        PolicyReplica(Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC), setOf(topic))

    private fun held(
        value: String?,
        scope: PolicyScope = PolicyScope.entity(tenant, "E1"),
        key: String = VoluntaryOverSubmission.VARIATION_POINT,
    ): VoluntaryOverSubmission {
        val policy = replica()
        if (value != null) {
            policy.accept(PolicyChanged(scope, key, value, start, PolicySource.PROFILE, null, topic, 1))
        }
        return VoluntaryOverSubmission(policy)
    }

    private fun isOn(
        value: String?,
        entity: String = "E1",
        scope: PolicyScope = PolicyScope.entity(tenant, "E1"),
        key: String = VoluntaryOverSubmission.VARIATION_POINT,
        asTenant: TenantId = tenant,
    ) = held(value, scope, key).isOn(asTenant, entity, date)

    @Test
    fun `INV-AC-25208 the option list ON at the entity scope is on`() {
        isOn(on) shouldBe true
        VoluntaryOverSubmission.ON shouldBe "ON"
        VoluntaryOverSubmission.VARIATION_POINT shouldBe "VP-21"
    }

    @Test
    fun `INV-AC-25208 no value is off`() {
        isOn(null) shouldBe false
    }

    @Test
    fun `INV-AC-25208 a value that is not the one-element ON list is off`() {
        listOf(
            """[{"key":"OFF"}]""",
            "ON", // the bare text no producer sends
            "\"ON\"",
            """[{"key":"ON"},{"key":"ON"}]""",
            "[]",
            """["ON"]""",
            """[{"key":"ON","extra":1}]""",
            """[{"key":"ON","value":3}]""",
            """[{}]""",
            """[{"key":1}]""",
            """[{"key":null}]""",
            """[{"key":"on"}]""",
            """[{"key":"ON "}]""",
            """{"key":"ON"}""",
            "null",
            "",
            "[{\"key\":\"ON\"",
            "not json",
        ).forEach { isOn(it) shouldBe false }
    }

    @Test
    fun `INV-AC-25208 a duplicate member or a trailing token is off`() {
        isOn("""[{"key":"OFF","key":"ON"}]""") shouldBe false
        isOn("""[{"key":"ON","key":"ON"}]""") shouldBe false
        isOn("$on []") shouldBe false
        isOn("$on x") shouldBe false
        isOn("$on$on") shouldBe false
    }

    @Test
    fun `INV-AC-25208 another entity or another variation point is off`() {
        isOn(on, entity = "E2") shouldBe false
        isOn(on, scope = PolicyScope.entity(tenant, "E2")) shouldBe false
        isOn(on, key = "VP-22") shouldBe false
        isOn(on, key = "VP-210") shouldBe false
    }

    @Test
    fun `INV-AC-25208 the platform tenant is off`() {
        // PolicyScope.entity refuses the platform tenant: the value sits at a tenant entity, only the guard denies.
        held(on).isOn(TenantId.PLATFORM, "E1", date) shouldBe false
        held(on).isOn(tenant, "E1", date) shouldBe true
    }
}
