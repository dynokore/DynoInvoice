package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.link.PemSealingKeys
import com.dynokore.gst.access.application.link.OaepPasscodeSealer
import com.dynokore.gst.access.application.link.PasscodeSealer
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The start-up key and the property of the signed link (INV-T-17453; INV-Q-17195 A, INV-Q-17203 A): the public key is
 * read once at start-up from the SecretStore binding `message-delivery-sealing-public-key` and a key that is unusable
 * stops the start-up; `access.signed-link.sms-gateway-bound` is a strict boolean that gates VP-75 SMS_ONLY. The tests
 * use key pairs generated here.
 */
class SignedLinkStartupTest {
    private fun runner(
        pem: String? = TestSecrets.pem(TestSecrets.deliveryKeyPair.public),
        vararg properties: String,
    ) = BoundaryFixture
        .runner(catalogueMatrix = true, form = "PRIVATE", mounted = BoundaryFixture.mountWithDeliveryKey(pem))
        .withPropertyValues(*properties)
        .withBean(
            EndUserPrincipalResolver::class.java,
            { TestTokenPrincipals(mapOf("token-operator" to EndUserPrincipal("user:operator", null, emptySet()))) },
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
        )

    private fun startupFailure(
        pem: String?,
        vararg properties: String,
    ): Throwable? {
        var failure: Throwable? = null
        runner(pem, *properties).run { failure = it.startupFailure }
        return failure
    }

    private fun smsOnly(context: AssertableWebApplicationContext): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-75")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer token-operator")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        val today = LocalDate.now(ZoneId.of("Asia/Singapore"))
        request.setContent(
            """{"scopeLevel":"INSTALLATION","selected":[{"option":"SMS_ONLY"}],"effectiveDate":"$today","reason":"sms"}"""
                .toByteArray(),
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

    private fun rsa(bits: Int) = TestSecrets.rsaKeyPair(bits).public

    @Test
    fun `INV-REQ-899 a 3072-bit RSA key starts the service and seals to its key id`() {
        runner().run { context ->
            context.startupFailure?.let { throw it }
            val sealer = context.getBean(PasscodeSealer::class.java)
            assertThat(sealer).isInstanceOf(OaepPasscodeSealer::class.java)
            assertThat(sealer.seal("482913", "a@b.example")).startsWith("v1.")
        }
    }

    @Test
    fun `INV-REQ-899 a 2048-bit key, an EC key and a missing secret each stop the start-up`() {
        val ec =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
                .public
        assertThat(startupFailure(TestSecrets.pem(rsa(2048)))).describedAs("2048-bit RSA").isNotNull()
        assertThat(startupFailure(TestSecrets.pem(ec))).describedAs("EC").isNotNull()
        assertThat(startupFailure(null)).describedAs("missing secret").isNotNull()
        assertThat(startupFailure("not a pem")).describedAs("garbage").isNotNull()
        assertThat(startupFailure("")).describedAs("empty").isNotNull()
    }

    @Test
    fun `INV-REQ-899 the key parser refuses what is not a 3072-bit RSA SubjectPublicKeyInfo`() {
        val key = PemSealingKeys.parse(TestSecrets.pem(TestSecrets.deliveryKeyPair.public))
        assertThat(key.key.modulus.bitLength()).isGreaterThanOrEqualTo(3072)
        val der = TestSecrets.pem(TestSecrets.deliveryKeyPair.public)
        listOf(
            der.replace("PUBLIC KEY", "RSA PUBLIC KEY"),
            der.replace("A", "!", ignoreCase = false),
            TestSecrets.pem(rsa(2048)),
        ).forEach { bad ->
            val refused = runCatching { PemSealingKeys.parse(bad) }
            assertThat(refused.isFailure).describedAs(bad.take(40)).isTrue()
        }
    }

    @Test
    fun `INV-REQ-899 sms-gateway-bound absent or false refuses SMS only and true accepts it`() {
        listOf(emptyArray(), arrayOf("access.signed-link.sms-gateway-bound=false")).forEach { properties ->
            runner(properties = properties).run { context ->
                context.startupFailure?.let { throw it }
                assertThat(smsOnly(context).status).describedAs(properties.joinToString()).isEqualTo(422)
            }
        }
        runner(properties = arrayOf("access.signed-link.sms-gateway-bound=true")).run { context ->
            context.startupFailure?.let { throw it }
            val accepted = smsOnly(context)
            assertThat(accepted.status).describedAs(accepted.contentAsString).isBetween(200, 299)
        }
    }

    @Test
    fun `INV-REQ-899 a sms-gateway-bound value that is not exactly true or false stops the start-up`() {
        listOf("yes", "on", "1", "TRUE", "True", "", "maybe", "true,false").forEach { value ->
            assertThat(
                startupFailure(
                    TestSecrets.pem(TestSecrets.deliveryKeyPair.public),
                    "access.signed-link.sms-gateway-bound=$value",
                ),
            ).describedAs("value '$value'")
                .isNotNull()
        }
    }
}
