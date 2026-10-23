package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.application.consent.AgentTenancy
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * INV-AC-2768 (INV-REQ-2757) in a private installation, through the mounted setPolicy servlet at installation scope:
 * VP-74 is off by default, `deployment.operator` switches it on, and the switch back off is refused while a consent is
 * live. The installation setting lives in the shared database, so the test ends with tenancy on, as it found no value.
 */
class AgentTenancyTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val signatory = "user:signatory"

    private fun run(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true, form = "PRIVATE")
            .withBean(
                EndUserPrincipalResolver::class.java,
                {
                    TestTokenPrincipals(
                        mapOf(
                            "token-operator" to EndUserPrincipal("user:operator", null, emptySet()),
                            "token-nobody" to EndUserPrincipal("user:nobody", null, emptySet()),
                        ),
                    )
                },
            ).withBean(
                PolicyRoleResolver::class.java,
                {
                    PolicyRoleResolver { actor, _, level, _, _ ->
                        if (level == ScopeLevel.INSTALLATION && actor == "user:operator") {
                            setOf("deployment.operator")
                        } else {
                            emptySet()
                        }
                    }
                },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun setTenancy(
        context: AssertableWebApplicationContext,
        option: String,
        token: String = "token-operator",
        key: String = "VP-74",
        selected: String = """[{"option":"$option"}]""",
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/$key")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer $token")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        val today = LocalDate.now(ZoneId.of("Asia/Singapore"))
        request.setContent(
            (
                """{"scopeLevel":"INSTALLATION","selected":$selected,""" +
                    """"effectiveDate":"$today","reason":"agent tenancy"}"""
            ).toByteArray(),
        )
        val response = MockHttpServletResponse()
        val registrations = context.getBeansOfType(ServletRegistrationBean::class.java).values
        val servlet =
            registrations
                .single {
                    it.urlMappings.contains(
                        PolicyCommandServlet.MAPPING,
                    )
                }.servlet as HttpServlet
        servlet.service(request, response)
        return response
    }

    private fun tenancyOn(context: AssertableWebApplicationContext): Boolean =
        context.getBean(TenantContext::class.java).bind(tenant, signatory) {
            context.getBean(TransactionRunner::class.java).inTransaction {
                context.getBean(AgentTenancy::class.java).enabled()
            }
        }

    private fun grantConsent(
        context: AssertableWebApplicationContext,
        client: String,
    ) = context.getBean(TenantContext::class.java).bind(tenant, signatory) {
        context
            .getBean(ConsentController::class.java)
            .grantConsent(
                UUID.randomUUID(),
                GrantConsentRequest(client, "user:agent", LocalDate.of(2026, 1, 1), "director@client.example"),
            )
    }

    private fun seedClients(context: AssertableWebApplicationContext) {
        listOf("CLI-A", "CLI-B").forEach { client ->
            val node = BoundaryFixture.entityNode()
            context
                .getBean(StructureReplica::class.java)
                .accept(OrganisationStructureChanged(tenant, client, StructureChange.CREATED, node))
            val signatorySeed = SeedSpec(signatory, Role.CLIENT_SIGNATORY, Scope.Entity(EntityRef(client)))
            Seeds.grant(context, tenant, signatorySeed)
        }
    }

    /** The shared database also holds the live consents of the other tests; a superuser withdraws them all. */
    private fun withdrawEveryConsent() =
        BoundaryFixture.asSuperuser { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "update access.consent set status = 'WITHDRAWN', " +
                        "handover_statement_ref = 'HOS-TEST-CLEANUP' where status = 'LIVE'",
                )
            }
        }

    @Test
    fun `INV-AC-2768 switching off agent tenancy is refused while a consent is live and agent tenancy stays on`() =
        run { context ->
            seedClients(context)
            resetInstallation(context)
            // a private installation has agent tenancy off until switched on (behaviour 2); no consent is recorded
            assertThat(tenancyOn(context)).isFalse()
            val off = runCatching { grantConsent(context, "CLI-A") }.exceptionOrNull() as ApiRefusal
            assertThat(off.code.code).isEqualTo("SRT-004")
            assertThat(off.detail).isEqualTo("agent tenancy is off in this installation")
            assertThat(consentRows(tenant)).isZero()
            assertThat(setTenancy(context, "ON", token = "token-nobody").status).isEqualTo(403)
            val on = setTenancy(context, "ON")
            assertThat(on.status).describedAs(on.contentAsString).isBetween(200, 299)
            assertThat(tenancyOn(context)).isTrue()
            assertThat(grantConsent(context, "CLI-A").statusCode.value()).isEqualTo(201)
            // the switch-off is refused, and agent tenancy stays on
            val refused = setTenancy(context, "OFF")
            assertThat(refused.status).describedAs(refused.contentAsString).isGreaterThanOrEqualTo(400)
            assertThat(tenancyOn(context)).isTrue()
            assertThat(grantConsent(context, "CLI-B").statusCode.value()).isEqualTo(201)
            // once every consent is withdrawn (also those other tests left in the shared database) it is accepted
            withdrawEveryConsent()
            val switchOff = setTenancy(context, "OFF")
            assertThat(switchOff.status).describedAs(switchOff.contentAsString).isBetween(200, 299)
            assertThat(tenancyOn(context)).isFalse()
            assertThat(setTenancy(context, "ON").status).isBetween(200, 299)
        }

    /** The database is shared by the tests of a run: no live consent, and VP-74 stored OFF as at the start. */
    private fun resetInstallation(context: AssertableWebApplicationContext) {
        withdrawEveryConsent()
        setTenancy(context, "OFF")
    }

    private fun consentRows(tenant: TenantId): Int =
        BoundaryFixture.asSuperuser { connection ->
            connection.prepareStatement("select count(*) from access.consent where tenant_id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(tenant.toString()))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "count returned no row" }
                    rows.getInt(1)
                }
            }
        }

    @Test
    fun `INV-AC-2768 look-alike keys and values do not switch agent tenancy off while a consent is live`() =
        run { context ->
            seedClients(context)
            resetInstallation(context)
            assertThat(setTenancy(context, "ON").status).isBetween(200, 299)
            assertThat(grantConsent(context, "CLI-A").statusCode.value()).isEqualTo(201)
            val rows = consentRows(tenant)
            val attempts =
                listOf(
                    setTenancy(context, "OFF", key = "VP-740"),
                    setTenancy(context, "OFF", key = "VP-73"),
                    setTenancy(context, "OFF2"),
                    setTenancy(context, "off"),
                    setTenancy(context, "ON,OFF"),
                    setTenancy(context, "OFF", selected = """[{"option":"ON"},{"option":"OFF"}]"""),
                )
            attempts.forEach { assertThat(it.status).describedAs(it.contentAsString).isGreaterThanOrEqualTo(400) }
            assertThat(tenancyOn(context)).isTrue()
            assertThat(consentRows(tenant)).isEqualTo(rows)
        }
}
