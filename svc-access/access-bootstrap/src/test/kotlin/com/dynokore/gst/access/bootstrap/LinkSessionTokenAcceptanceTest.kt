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
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
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
import java.util.UUID

/**
 * INV-T-17805 (INV-REQ-899, ruling option A of the owner): the link-session token of `verifySignatoryLink` in the
 * application context over a real PostgreSQL, with the real signer and the outbox: the token, its key in the event
 * `OperatorSessionKeysChanged`, the rotation, the kid separation, the logs and the replica round trip.
 */
class LinkSessionTokenAcceptanceTest {
    private val mapper = JsonMapper.builder().build()
    private val digests = Sha256PasscodeDigests()
    private val passcode = "482913"
    private val tenant = TenantId(UUID.randomUUID())

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val api = context.getBean(SignedLinksController::class.java)

        fun enter(
            ref: String,
            entry: String,
        ) = api.verifySignatoryLink(ref, UUID.randomUUID(), VerifySignatoryLinkRequest(entry))
    }

    private fun run(
        linkKey: RSAKey = TestSecrets.defaultLinkKey,
        next: RSAKey? = null,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.deleteLinkSessionKeys()
        BoundaryFixture
            .runner(catalogueMatrix = true, mounted = BoundaryFixture.mountLink(linkKey, next), requirements = null)
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    private fun seed(
        state: String = "ACTIVE",
        expiresAt: Instant = Instant.now().plusSeconds(3600),
    ): String {
        val ref = "LNK-" + UUID.randomUUID().toString().replace("-", "").take(22)
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

    private fun events(): List<JsonNode> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select data::text from access.outbox where type like '%OperatorSessionKeysChanged%' " +
                            "order by seq",
                    ).use { rs -> buildList { while (rs.next()) add(mapper.readTree(rs.getString(1))) } }
            }
        }

    private fun linkItems(event: JsonNode) = event["keys"].filter { it["use"].asString() == "LINK_SESSION" }

    private fun publicKey(item: JsonNode): RSAPublicKey =
        KeyFactory
            .getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(item["publicKey"].asString()))) as RSAPublicKey

    private fun kidOf(key: RSAKey): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toRSAPublicKey().encoded)
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private fun privateBytes(key: RSAKey): List<String> =
        listOf(
            key.privateExponent.toString(),
            Base64.getEncoder().encodeToString(key.toPrivateKey().encoded).take(64),
            "PRIVATE KEY",
        )

    private fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val before = root.level
        root.level = Level.TRACE
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            root.level = before
        }
        return appender.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
    }

    private fun assertNoToken(
        token: String,
        logs: List<String>,
    ) {
        assertThat(logs.filter { token in it }).describedAs("log lines holding the link-session token").isEmpty()
        val stored =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { st ->
                    val tables =
                        st
                            .executeQuery(
                                "select table_name from information_schema.tables where table_schema = 'access' " +
                                    "and (table_name = 'outbox' or table_name like '%audit%')",
                            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    assertThat(tables).contains("outbox")
                    tables.flatMap { table ->
                        st.executeQuery("select to_jsonb(t)::text from access.$table t").use { rs ->
                            buildList { while (rs.next()) add(rs.getString(1)) }
                        }
                    }
                }
            }
        assertThat(stored.filter { token in it }).describedAs("outbox payloads and audit entries").isEmpty()
    }

    @Test
    fun `INV-AC-17810 a right passcode returns the token that the published LINK_SESSION key verifies`() =
        run { env ->
            val ref = seed()
            val key = TestSecrets.defaultLinkKey
            val body = env.enter(ref, passcode).body!!
            val token = body.sessionToken!!
            val jwt = SignedJWT.parse(token)
            val event = events().last()
            val items = linkItems(event)
            assertThat(items.map { it["kid"].asString() }).contains(kidOf(key))
            val item = items.single { it["kid"].asString() == jwt.header.keyID }
            assertThat(jwt.verify(RSASSAVerifier(publicKey(item)))).isTrue()
            // not an operator-session or machine key: no other item has this kid, and another RSA key does not verify
            assertThat(event["keys"].filter { it["kid"].asString() == jwt.header.keyID }).hasSize(1)
            assertThat(jwt.verify(RSASSAVerifier(TestSecrets.newLinkKey().toRSAPublicKey()))).isFalse()
            assertThat(jwt.header.type.type).isEqualTo("link-session+jwt")
            assertThat(jwt.header.algorithm.name).isEqualTo("RS256")
            val claims = jwt.jwtClaimsSet
            assertThat(claims.subject).isEqualTo("link:$ref")
            assertThat(claims.getStringClaim("tenant_id")).isEqualTo(tenant.toString())
            assertThat(claims.claims.keys).containsExactlyInAnyOrder("sub", "tenant_id", "iat", "exp", "jti")
            assertThat(body.sessionExpiresAt!!.toInstant()).isEqualTo(claims.expirationTime.toInstant())
            assertThat(Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant()))
                .isLessThanOrEqualTo(Duration.ofMinutes(15))
            // no private key bytes in the event
            privateBytes(key).forEach { assertThat(event.toString()).doesNotContain(it) }
            assertThat(item["alg"].asString()).isEqualTo("RS256")
        }

    @Test
    fun `INV-AC-17810 a wrong passcode, the fifth entry and a revoked link return no token`() =
        run { env ->
            val ref = seed()
            val wrong = env.enter(ref, "000000").body!!
            assertThat(wrong.verified).isFalse()
            assertThat(wrong.sessionToken).isNull()
            assertThat(wrong.sessionExpiresAt).isNull()
            repeat(3) { env.enter(ref, "000000") }
            val fifth = env.enter(ref, "000000").body!!
            assertThat(fifth.invalidated).isTrue()
            assertThat(fifth.sessionToken).isNull()
            assertThat(fifth.sessionExpiresAt).isNull()
            assertThatThrownBy { env.enter(ref, passcode) }.isInstanceOf(ApiLinkInvalidated::class.java)
            val revoked = seed(state = "REVOKED")
            assertThatThrownBy { env.enter(revoked, passcode) }.isInstanceOf(ApiLinkInvalidated::class.java)
        }

    @Test
    fun `INV-AC-17810 the token ends at the earlier of the link expiry and 15 minutes`() =
        run { env ->
            val soon = Instant.now().plusSeconds(120)
            val body = env.enter(seed(expiresAt = soon), passcode).body!!
            assertThat(body.sessionExpiresAt!!.toInstant()).isBeforeOrEqualTo(soon)
            assertThat(body.sessionExpiresAt!!.toInstant()).isAfter(Instant.now())
        }

    @Test
    fun `INV-AC-17810 a rotation emits one event with both link keys and no private bytes`() {
        val current = TestSecrets.newLinkKey()
        val next = TestSecrets.newLinkKey()
        run(current, next) { env ->
            val before = events().size
            val token = env.enter(seed(), passcode).body!!.sessionToken!!
            val after = events()
            assertThat(after.size).isEqualTo(before + 1)
            val event = after.last()
            assertThat(linkItems(event).map { it["kid"].asString() })
                .containsExactlyInAnyOrder(kidOf(current), kidOf(next))
            assertThat(event["keys"].size()).isLessThanOrEqualTo(6)
            assertThat(SignedJWT.parse(token).header.keyID).isEqualTo(kidOf(current))
            (privateBytes(current) + privateBytes(next)).forEach { assertThat(event.toString()).doesNotContain(it) }
            // a second call publishes nothing new
            env.enter(seed(), passcode)
            assertThat(events().size).isEqualTo(before + 1)
        }
    }

    @Test
    fun `INV-AC-17810 a link-session kid shared with a machine key publishes nothing and logs an error`() {
        val key = TestSecrets.defaultLinkKey
        val platform = UUID.fromString(TenantId.PLATFORM.toString())
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("delete from access.verification_key where kid = ?").use {
                it.setString(1, kidOf(key))
                it.executeUpdate()
            }
            c.prepareStatement(
                "insert into access.verification_key (id, tenant_id, kid, alg, public_key, valid_from, valid_until, " +
                    "use) values (?, ?, ?, 'ES256', 'x', now(), now() + interval '1 day', 'MACHINE_ACCESS')",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, platform)
                it.setString(3, kidOf(key))
                it.executeUpdate()
            }
        }
        try {
            run { env ->
                val before = events().size
                val logs =
                    captureLogs {
                        assertThatThrownBy { env.enter(seed(), passcode) }
                            .isInstanceOfSatisfying(ApiRefusal::class.java) {
                                assertThat(it.code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
                            }
                    }
                assertThat(events().size).isEqualTo(before)
                assertThat(logs.any { "stored under two uses" in it }).isTrue()
                val rows =
                    BoundaryFixture.asSuperuser { c ->
                        c.createStatement().use { st ->
                            st.executeQuery("select count(*) from access.link_session_key").use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    }
                assertThat(rows).isZero()
            }
        } finally {
            BoundaryFixture.asSuperuser { c ->
                c.prepareStatement("delete from access.verification_key where kid = ?").use {
                    it.setString(1, kidOf(key))
                    it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `INV-AC-17810 no log line, outbox payload or audit entry holds the token`() =
        run { env ->
            val ref = seed()
            lateinit var token: String
            val logs = captureLogs { token = env.enter(ref, passcode).body!!.sessionToken!! }
            assertNoToken(token, logs)
            // probe: a deliberate log line with the token makes the check fail
            val probe =
                captureLogs { LoggerFactory.getLogger("probe").info("leak {}", token) }
            assertThatThrownBy { assertNoToken(token, probe) }.isInstanceOf(AssertionError::class.java)
        }

    @Test
    fun `INV-AC-17810 after the removal record the token still verifies and the replica decides NO_GRANT`() =
        run { env ->
            val ref = seed()
            val body = env.enter(ref, passcode).body!!
            val jwt = SignedJWT.parse(body.sessionToken!!)
            val item = linkItems(events().last()).single { it["kid"].asString() == jwt.header.keyID }
            val sub = jwt.jwtClaimsSet.subject
            assertThat(sub).isEqualTo("link:$ref")
            val clock = Clock.systemUTC()
            val replica =
                AuthorityReplica(clock, OperationRoleMap { mapOf("signatory.fixture" to CellAllowance.PERMITTED) })
            replica.onSnapshotComplete(0)
            val role = RoleGrant("signatory.fixture", GrantScope.ENTITY, "CLI-A", Instant.now().minusSeconds(60))
            fun change(
                version: Long,
                state: RoleGrantState,
            ) = replica.accept(
                AuthorityChanged(tenant, sub, version, AuthorityRecord.RoleGrantRecord("g-link", state, role)),
            )

            fun decide() =
                replica.decide(
                    AuthorizationRequest(tenant, Principal(sub, PrincipalKind.HUMAN), "CLI-A", "any.action"),
                    Deadline.after(Duration.ofSeconds(5), clock),
                )
            change(1, RoleGrantState.ACTIVE)
            assertThat(decide().allowed).isTrue()
            // the link is revoked or invalidated: the removal record of the role grant
            change(2, RoleGrantState.REMOVED)
            val decision = decide()
            assertThat(decision.allowed).isFalse()
            assertThat(decision.reason).isEqualTo(DecisionReason.NO_GRANT)
            // the token is a bearer credential: it still verifies and is not expired
            assertThat(jwt.verify(RSASSAVerifier(publicKey(item)))).isTrue()
            assertThat(jwt.jwtClaimsSet.expirationTime.toInstant()).isAfter(Instant.now())
        }
}
