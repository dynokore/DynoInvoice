package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.gate.AuthorityReplicaOverrideAuthority
import com.dynokore.gst.submission.adapters.gate.HttpReleaseDecisions
import com.dynokore.gst.submission.adapters.gate.PolicyReplicaGateModes
import com.dynokore.gst.submission.adapters.persistence.ReadinessLatestChecks
import com.dynokore.gst.submission.application.gate.VALIDATION_OVERRIDE
import com.dynokore.gst.submission.application.readiness.MAX_READINESS_PAGE
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.REFUSAL_CODE
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PolicyChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.PolicySource
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class GateAdaptersTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val start = Instant.parse("2026-11-10T00:00:00Z")
    private val day = LocalDate.of(2026, 11, 10)

    private class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock(start)

    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    // ---- PolicyReplicaGateModes (VP-14) -------------------------------------------------------------------------

    private fun modes(value: String?): PolicyReplicaGateModes {
        val policy = PolicyReplica(clock, setOf(TOPIC))
        if (value != null) {
            policy.accept(
                PolicyChanged(
                    PolicyScope.entity(tenant, "E1"),
                    "VP-14",
                    value,
                    day,
                    PolicySource.PROFILE,
                    null,
                    TOPIC,
                    1,
                ),
            )
        }
        return PolicyReplicaGateModes(policy, tenants, clock)
    }

    @Test
    fun `an absent VP-14 value is ADVISORY and the two option keys are read`() {
        modes(null).modeOf("E1") shouldBe GateMode.ADVISORY
        modes("ADVISORY").modeOf("E1") shouldBe GateMode.ADVISORY
        modes("BLOCKING").modeOf("E1") shouldBe GateMode.BLOCKING
    }

    @Test
    fun `VP-14 value BLOCKING with a trailing space is an error`() {
        assertThrows<IllegalArgumentException> { modes("BLOCKING ").modeOf("E1") }
    }

    @Test
    fun `VP-14 value blocking in lower case is an error`() {
        assertThrows<IllegalArgumentException> { modes("blocking").modeOf("E1") }
    }

    @Test
    fun `VP-14 value BLOCK is an error`() {
        assertThrows<IllegalArgumentException> { modes("BLOCK").modeOf("E1") }
    }

    @Test
    fun `the value of another entity is not applied`() {
        modes("BLOCKING").modeOf("E2") shouldBe GateMode.ADVISORY
    }

    // ---- HttpReleaseDecisions -------------------------------------------------------------------------------------

    private class Access(
        val server: MockRestServiceServer,
        val decisions: HttpReleaseDecisions,
    )

    private fun access(
        status: HttpStatus,
        body: String,
    ): Access {
        val builder = RestClient.builder().baseUrl("http://access.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect { request -> request.uri.path shouldBe "/internal/v1/access/override-release-decision" }
            .andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("entityRef", "E1"))
            .andExpect(queryParam("errorCode", REFUSAL_CODE))
            .andExpect(queryParam("approverRef", "A2"))
            .andExpect(queryParam("priorApproverRef", "A1"))
            .andExpect { request ->
                val query = request.uri.query
                query shouldNotContain "documentRef"
                query shouldNotContain "documentVersion"
                query shouldNotContain "documentTotalSgd"
            }.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
        return Access(server, HttpReleaseDecisions(builder.build()))
    }

    private fun decide(
        status: HttpStatus,
        body: String,
    ): ReleaseOutcome {
        val a = access(status, body)
        return a.decisions.decide("E1", REFUSAL_CODE, "A2", listOf("A1")).also { a.server.verify() }
    }

    @Test
    fun `ALLOW RELEASED releases and ALLOW AWAITS_SECOND_APPROVER awaits`() {
        decide(HttpStatus.OK, """{"decision":"ALLOW","soleOperator":false,"outcome":"RELEASED"}""") shouldBe
            ReleaseOutcome.Released
        val awaits = """{"decision":"ALLOW","soleOperator":false,"outcome":"AWAITS_SECOND_APPROVER"}"""
        decide(HttpStatus.OK, awaits) shouldBe ReleaseOutcome.AwaitsSecond
    }

    @Test
    fun `a DENY carries its reason code and sends no document reference, version or total`() {
        decide(HttpStatus.OK, """{"decision":"DENY","soleOperator":false,"reasonCode":"SAME_PERSON_TWICE"}""") shouldBe
            ReleaseOutcome.Denied("SAME_PERSON_TWICE")
    }

    @Test
    fun `an unknown outcome, a trailing space, an ALLOW without outcome and a lower case outcome are Denied`() {
        decide(HttpStatus.OK, """{"decision":"ALLOW","soleOperator":false,"outcome":"RELEASED "}""") shouldBe denied()
        decide(HttpStatus.OK, """{"decision":"ALLOW ","soleOperator":false,"outcome":"RELEASED"}""") shouldBe denied()
        decide(HttpStatus.OK, """{"decision":"ALLOW","soleOperator":false}""") shouldBe denied()
        decide(HttpStatus.OK, """{"decision":"ALLOW","soleOperator":false,"outcome":"released"}""") shouldBe denied()
    }

    @Test
    fun `an error status, an unreadable answer and a connection failure are Denied`() {
        decide(HttpStatus.INTERNAL_SERVER_ERROR, """{"title":"x"}""") shouldBe denied()
        decide(HttpStatus.SERVICE_UNAVAILABLE, "") shouldBe denied()
        decide(HttpStatus.OK, "not json") shouldBe denied()
        decide(HttpStatus.OK, "{}") shouldBe denied()
        val builder = RestClient.builder().baseUrl("http://access.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { }.andRespond { throw java.io.IOException("down") }
        HttpReleaseDecisions(builder.build()).decide("E1", REFUSAL_CODE, "A2", emptyList()) shouldBe denied()
    }

    private fun denied() = ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)

    // ---- source scan ----------------------------------------------------------------------------------------------

    @Test
    fun `source scan finds no role name as a string literal in the production code of adapters and bootstrap`() {
        val roots = listOf("src/main", "../submission-bootstrap/src/main").map(::File).filter { it.isDirectory }
        roots.size shouldBe 2
        val offenders =
            roots.flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && roleLiterals(it.readText()).isNotEmpty() }
                    .map { it.path }
                    .toList()
            }
        offenders shouldBe emptyList()
    }

    @Test
    fun `source scan fails a copy that adds a role name literal`() {
        for (role in ROLES) {
            roleLiterals("val x = \"$role\"") shouldBe listOf(role)
            roleLiterals("check(role == \"$role\") // \"$role\"") shouldBe listOf(role, role)
        }
        roleLiterals("val x = \"tax.approver2\"") shouldBe emptyList()
    }

    private companion object {
        const val TOPIC = "policy-topic"
        val ROLES = listOf("tax.approver", "tenant.admin", "agent.operator", "entity.admin")

        private val ROLE_LITERAL = Regex("\"(${ROLES.joinToString("|") { Regex.escape(it) }})\"")

        fun roleLiterals(source: String): List<String> = ROLE_LITERAL.findAll(source).map { it.groupValues[1] }.toList()
    }
}
