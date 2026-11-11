package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.access.adapters.http.ApiLinkInvalidated
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.SignedLinksController
import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.contract.model.VerifySignatoryLinkRequest
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.JwtEndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

/** INV-T-17805: the link-session key, its publication and the closed refusal when it cannot sign. */
class LinkSessionKeyAcceptanceTest : LinkSessionTestSupport() {
    private fun storedRows(): Map<String, String> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { st ->
                st.executeQuery("select kid, public_key from access.link_session_key").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
                }
            }
        }

    private fun insertRow(
        key: RSAKey,
        validFrom: Instant,
    ) = superuserUpdate(
        "insert into access.link_session_key (id, tenant_id, kid, alg, public_key, valid_from, valid_until) " +
            "values (?, ?, ?, 'RS256', ?, ?, ?)",
        UUID.randomUUID(),
        UUID.fromString(TenantId.PLATFORM.toString()),
        kidOf(key),
        Base64.getEncoder().encodeToString(key.toRSAPublicKey().encoded),
        Timestamp.from(validFrom),
        Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
    )

    private fun assertUnavailable(
        env: Env,
        ref: String,
    ) {
        assertThatThrownBy { env.enter(ref, passcode) }
            .isInstanceOfSatisfying(ApiRefusal::class.java) {
                assertThat(it.code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
            }
    }

    private val secretName = "access/link-session-signing-key"

    @Test
    fun `INV-AC-17810 an absent key secret is ISSUER_UNAVAILABLE, a retry once it is back gives a token`() {
        val root = BoundaryFixture.mountLink(null)
        run(mounted = root) { env ->
            val ref = seed()
            assertUnavailable(env, ref)
            java.nio.file.Files
                .writeString(root.resolve(secretName), TestSecrets.defaultLinkKey.toJSONString())
            assertThat(env.enter(ref, passcode).body!!.sessionToken).isNotNull()
        }
    }

    @Test
    fun `INV-AC-17810 an unreadable key secret is ISSUER_UNAVAILABLE, a retry once it is back gives a token`() {
        val root = BoundaryFixture.mountLink(TestSecrets.defaultLinkKey)
        val file = root.resolve(secretName)
        java.nio.file.Files
            .writeString(file, "this is not a key")
        run(mounted = root) { env ->
            val ref = seed()
            assertUnavailable(env, ref)
            // a public-only key and an RSA key below 2048 bits are not signing keys either
            java.nio.file.Files
                .writeString(file, TestSecrets.defaultLinkKey.toPublicJWK().toJSONString())
            assertUnavailable(env, ref)
            java.nio.file.Files.writeString(
                file,
                com.nimbusds.jose.jwk.gen
                    .RSAKeyGenerator(1024, true)
                    .generate()
                    .toJSONString(),
            )
            assertUnavailable(env, ref)
            java.nio.file.Files
                .writeString(file, TestSecrets.defaultLinkKey.toJSONString())
            assertThat(env.enter(ref, passcode).body!!.sessionToken).isNotNull()
        }
    }

    @Test
    fun `INV-AC-17810 a key outside its signing interval is ISSUER_UNAVAILABLE and the next key is published first`() {
        val current = TestSecrets.newLinkKey()
        val next = TestSecrets.newLinkKey()
        run(current, next) { env ->
            // PT1H interval: a row valid from two hours ago is past its signing interval
            insertRow(current, Instant.now().minus(Duration.ofHours(2)))
            assertUnavailable(env, seed())
            // the next key's row exists although the current key did not sign
            assertThat(storedRows().keys).contains(kidOf(next))
        }
        run(current) { env ->
            insertRow(current, Instant.now().minus(Duration.ofHours(2)))
            assertUnavailable(env, seed())
        }
    }

    @Test
    fun `INV-AC-17810 the stored kid is the key thumbprint and no next secret gives one row`() {
        val current = TestSecrets.newLinkKey()
        val next = TestSecrets.newLinkKey()
        run(current, next) { env ->
            env.enter(seed(), passcode)
            val rows = storedRows()
            assertThat(rows.keys).containsExactlyInAnyOrder(kidOf(current), kidOf(next))
            listOf(current, next).forEach {
                assertThat(rows.getValue(kidOf(it)))
                    .isEqualTo(Base64.getEncoder().encodeToString(it.toRSAPublicKey().encoded))
            }
            // probe: the thumbprint of another key is not a stored kid
            assertThat(rows.keys).doesNotContain(kidOf(TestSecrets.newLinkKey()))
        }
        run(current) { env ->
            assertThat(env.enter(seed(), passcode).body!!.sessionToken).isNotNull()
            assertThat(storedRows().keys).containsExactly(kidOf(current))
        }
    }

    @Test
    fun `INV-AC-17810 sessionExpiresAt is whole seconds and equals the token exp`() =
        run { env ->
            val expires = Instant.now().plusSeconds(120).plusMillis(437)
            val body = env.enter(seed(expiresAt = expires), passcode).body!!
            val exp =
                SignedJWT
                    .parse(body.sessionToken!!)
                    .jwtClaimsSet.expirationTime
                    .toInstant()
            assertThat(body.sessionExpiresAt!!.toInstant().nano).isZero()
            assertThat(body.sessionExpiresAt!!.toInstant()).isEqualTo(exp)
        }
}
