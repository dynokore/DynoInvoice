package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiLinkInvalidated
import com.dynokore.gst.access.adapters.http.SignedLinksController
import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.application.link.LinkPasscodeSetting
import com.dynokore.gst.access.application.link.LinkPasscodeSettings
import com.dynokore.gst.access.contract.model.VerifySignatoryLinkRequest
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * VP-75 at installation scope, set through the mounted `setPolicy` servlet (INV-T-4940; INV-REQ-2758): five wrong
 * entries by default, fewer when the installation sets it, never more; SMS only combines with a smaller limit. One
 * sequential test, because the installation value is shared by the tests of a run and a tightening is not undone.
 */
class SignedLinkSettingTest {
    private val tenant = TenantId(UUID.randomUUID())

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
                SetterPermission::class.java,
                {
                    SetterPermission { actor, _, level, _, _, _ ->
                        level == ScopeLevel.INSTALLATION && actor.principal == "user:operator"
                    }
                },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun setLimit(
        context: AssertableWebApplicationContext,
        selected: String,
        token: String = "token-operator",
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-75")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer $token")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        val today = LocalDate.now(ZoneId.of("Asia/Singapore"))
        request.setContent(
            (
                """{"scopeLevel":"INSTALLATION","selected":$selected,""" +
                    """"effectiveDate":"$today","reason":"signed-link passcode"}"""
            ).toByteArray(),
        )
        val response = MockHttpServletResponse()
        val servlet =
            context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
                .servlet as HttpServlet
        servlet.service(request, response)
        return response
    }

    private fun setting(context: AssertableWebApplicationContext): LinkPasscodeSetting =
        context.getBean(TenantContext::class.java).bind(tenant, "user:reader") {
            context.getBean(TransactionRunner::class.java).inTransaction {
                context.getBean(LinkPasscodeSettings::class.java).current()
            }
        }

    private fun seed(ref: String) =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "insert into access.signed_link (id, tenant_id, link_ref, purpose, client_ref, consent_ref, " +
                        "alert_entity, recipient_email, expires_at, passcode_digest, status, wrong_entries, source, " +
                        "created_at, created_by, updated_at, updated_by, version) " +
                        "values (?, ?, ?, 'CONFIRM_FILING', 'CLI-A', " +
                        "'CNS-1', 'CLI-A', 'signatory@client.example', ?, ?, 'ACTIVE', 0, 'CALL', now(), 'test', " +
                        "now(), " +
                        "'test', 0)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.fromString(tenant.toString()))
                    statement.setString(3, ref)
                    statement.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(3600)))
                    statement.setString(5, Sha256PasscodeDigests().digestOf(LinkRef(ref), "482913"))
                    statement.executeUpdate()
                }
        }

    /** The installation value is shared by the tests of a run and a tightening is not undone through the API. */
    @AfterEach
    fun forgetInstallationValue() {
        BoundaryFixture.asSuperuser { connection ->
            connection.createStatement().use {
                it.executeUpdate("delete from access.config_installation_setting where vp_key = 'VP-75'")
                it.executeUpdate("delete from access.config_resolved_value where vp_key = 'VP-75'")
            }
        }
    }

    @Test
    fun `INV-AC-2765 five wrong entries by default, fewer through VP-75, never more`() =
        run { context ->
            // the default: five wrong entries, not SMS only
            assertThat(setting(context)).isEqualTo(LinkPasscodeSetting(5, smsOnly = false))
            // a person without the permission is refused, and nothing changes
            assertThat(setLimit(context, """[{"option":"FEWER_WRONG_ENTRIES","value":3}]""", "token-nobody").status)
                .isEqualTo(403)
            assertThat(setting(context).maxWrongEntries).isEqualTo(5)
            // more than four, and less than one, are refused: the limit never exceeds five
            listOf("5", "6", "0", "-1", "2.5", "\"3\"").forEach { value ->
                val refused = setLimit(context, """[{"option":"FEWER_WRONG_ENTRIES","value":$value}]""")
                assertThat(refused.status).describedAs("$value ${refused.contentAsString}").isGreaterThanOrEqualTo(400)
            }
            assertThat(setting(context).maxWrongEntries).isEqualTo(5)
            // SMS only needs a fact access-svc cannot check, so it is refused (fail closed) and nothing changes
            val smsOnly = setLimit(context, """[{"option":"SMS_ONLY"}]""")
            assertThat(smsOnly.status).describedAs(smsOnly.contentAsString).isEqualTo(422)
            assertThat(setting(context)).isEqualTo(LinkPasscodeSetting(5, smsOnly = false))
            // three is accepted, and the third wrong entry invalidates the link
            val three = setLimit(context, """[{"option":"FEWER_WRONG_ENTRIES","value":3}]""")
            assertThat(three.status).describedAs(three.contentAsString).isBetween(200, 299)
            assertThat(setting(context)).isEqualTo(LinkPasscodeSetting(3, smsOnly = false))
            val api = context.getBean(SignedLinksController::class.java)
            val ref =
                "LNK-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(22)
            seed(ref)
            val wrongEntry = VerifySignatoryLinkRequest("000000")
            val answers = (1..3).map { api.verifySignatoryLink(ref, UUID.randomUUID(), wrongEntry).body!! }
            assertThat(answers.map { it.wrongEntryCount }).containsExactly(1, 2, 3)
            assertThat(answers.map { it.invalidated }).containsExactly(false, false, true)
            assertThatThrownBy {
                api.verifySignatoryLink(ref, UUID.randomUUID(), VerifySignatoryLinkRequest("482913"))
            }.isInstanceOf(ApiLinkInvalidated::class.java)
        }
}
