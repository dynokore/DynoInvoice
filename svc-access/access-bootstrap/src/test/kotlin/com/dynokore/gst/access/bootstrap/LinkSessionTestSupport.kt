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

/** Shared fixture of the link-session acceptance tests (INV-T-17805): a real PostgreSQL context, a stored link. */
open class LinkSessionTestSupport {
    protected val mapper = JsonMapper.builder().build()
    protected val digests = Sha256PasscodeDigests()
    protected val passcode = "482913"
    protected val tenant = TenantId(UUID.randomUUID())

    protected inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val api = context.getBean(SignedLinksController::class.java)

        fun enter(
            ref: String,
            entry: String,
        ) = api.verifySignatoryLink(ref, UUID.randomUUID(), VerifySignatoryLinkRequest(entry))
    }

    protected fun run(
        linkKey: RSAKey = TestSecrets.defaultLinkKey,
        next: RSAKey? = null,
        mounted: java.nio.file.Path = BoundaryFixture.mountLink(linkKey, next),
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.deleteLinkSessionKeys()
        BoundaryFixture
            .runner(catalogueMatrix = true, mounted = mounted, requirements = null)
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    protected fun seed(
        state: String = "ACTIVE",
        expiresAt: Instant = Instant.now().plusSeconds(3600),
    ): String {
        val ref =
            "LNK-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(22)
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "insert into access.signed_link (id, tenant_id, link_ref, purpose, client_ref, consent_ref, " +
                        "subject_reference, alert_entity, recipient_email, expires_at, passcode_digest, status, " +
                        "wrong_entries, invalidated_at, source, source_event_id, created_at, created_by, " +
                        "updated_at, updated_by, version) values (?, ?, ?, 'CONFIRM_FILING', 'CLI-A', 'CNS-1', " +
                        "'REQ-1', 'CLI-A', 'signatory@client.example', ?, ?, ?, 0, ?, 'CALL', null, now(), " +
                        "'test', now(), 'test', 0)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.fromString(tenant.toString()))
                    statement.setString(3, ref)
                    statement.setTimestamp(4, Timestamp.from(expiresAt))
                    statement.setString(5, digests.digestOf(LinkRef(ref), passcode))
                    statement.setString(6, state)
                    statement.setTimestamp(7, if (state == "INVALIDATED") Timestamp.from(Instant.now()) else null)
                    statement.executeUpdate()
                }
        }
        return ref
    }

    protected fun events(): List<JsonNode> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select data::text from access.outbox where type like '%OperatorSessionKeysChanged%' " +
                            "order by seq",
                    ).use { rs -> buildList { while (rs.next()) add(mapper.readTree(rs.getString(1))) } }
            }
        }

    protected fun linkItems(event: JsonNode) = event["keys"].filter { it["use"].asString() == "LINK_SESSION" }

    protected fun publicKey(item: JsonNode): RSAPublicKey =
        KeyFactory
            .getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(item["publicKey"].asString())))
            as RSAPublicKey

    protected fun kidOf(key: RSAKey): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toRSAPublicKey().encoded)
            .take(8)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    protected fun superuserUpdate(
        sql: String,
        vararg params: Any,
    ) = BoundaryFixture.asSuperuser { c ->
        c.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeUpdate()
        }
    }
}
