package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.IssueAccessTokenResponse.Decision
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale

/**
 * INV-AC-8848 at the service boundary (INV-T-8850): the real context over a real PostgreSQL, the gateway-side
 * controllers called directly, the secret store a mounted directory the tests rewrite, and a clock the tests move.
 * Each done-when item of the task is one test; each probe breaks the thing it guards and must be seen to fail.
 */
class TokenAcceptanceTest {
    private val mapper = JsonMapper.builder().build()

    private fun run(
        vararg extra: String,
        block: (TokenKit) -> Unit,
    ) {
        BoundaryFixture.deleteVerificationKeys()
        val key = TestSecrets.newSigningKey()
        val mounted = BoundaryFixture.mount(key)
        BoundaryFixture.runner(mounted = mounted).withPropertyValues(*extra).run { context ->
            context.startupFailure?.let { throw it }
            val tokens = TokenKit(PrincipalKit(context), mounted, context.getBean(SignerCalls::class.java))
            try {
                block(tokens)
            } finally {
                tokens.close()
            }
        }
    }

    private class Subject(
        val ref: String,
        val proof: String,
    )

    private fun TokenKit.principal(
        type: String,
        scope: String,
    ): Subject {
        val key = kit.newKey("client-key-" + java.util.UUID.randomUUID())
        val created = kit.create(type, scope = scope, method = PrincipalKit.JWT, key = key)
        return Subject(created.principalRef, kit.proof(key, created.principalRef))
    }

    private fun TokenKit.fresh(subject: Subject) = issue(subject.ref, subject.proof)

    private fun claimsOf(token: String): Map<String, Any?> = SignedJWT.parse(token).jwtClaimsSet.claims

    private fun scopeMembers(claims: Map<String, Any?>): List<String> =
        when (val scope = claims["scope"]) {
            is String -> scope.split(' ').filter { it.isNotEmpty() }
            is Collection<*> -> scope.map { it.toString() }
            else -> emptyList()
        }

    /** The failed checks of the claim table of INV-T-8850 for [claims] of an INGEST principal of entity scope. */
    private fun claimFindings(
        claims: Map<String, Any?>,
        expected: Expected,
        previousIds: Set<String> = emptySet(),
    ): List<String> =
        buildList {
            if (claims["iss"] != TestSecrets.TOKEN_ISSUER) add("iss")
            if (claims["sub"] != expected.ref || claims["client_id"] != expected.ref) add("sub")
            if (TestSecrets.TOKEN_AUDIENCE !in (claims["aud"] as? Collection<*> ?: listOf(claims["aud"]))) add("aud")
            if (scopeMembers(claims) != listOf(expected.scope)) add("scope")
            if (claims["principal_scope"] != expected.principalScope) add("principal_scope")
            if (claims["tenant_id"] != expected.tenant) add("tenant_id")
            if ((claims["permissions"] as? Collection<*>)?.map { it.toString() }?.toSet() != expected.permissions) {
                add("permissions")
            }
            if (claims["jti"] == null || claims["jti"] in previousIds) add("jti")
        }

    private class Expected(
        val ref: String,
        val scope: String,
        val principalScope: String,
        val tenant: String?,
        val permissions: Set<String>,
    )

    private fun permissionsOf(type: PrincipalType) = MachinePermissionSets.STANDARD.permissionsOf(type)

    private fun epoch(instant: Instant) = instant.epochSecond

    @Test
    fun `INV-AC-8848 claims of an ingest principal of entity scope`() =
        run { t ->
            val now = t.kit.clock.freeze()
            t.kit.entity("ENT-1")
            val ingest = t.principal("INGEST", "entity:ENT-1")
            val first = t.fresh(ingest)
            assertThat(first.decision).isEqualTo(Decision.ISSUED)
            val token = SignedJWT.parse(first.accessToken!!)
            assertThat(token.header.type.type).isEqualTo("at+jwt")
            assertThat(token.header.algorithm.name).isEqualTo("ES256")
            assertThat(token.header.keyID).isEqualTo(first.keyId)
            val claims = claimsOf(first.accessToken!!)
            val expected =
                Expected(
                    ingest.ref,
                    "ingest",
                    "entity:ENT-1",
                    t.kit.tenant.toString(),
                    permissionsOf(PrincipalType.INGEST),
                )
            assertThat(claimFindings(claims, expected)).isEmpty()
            assertThat(permissionsOf(PrincipalType.INGEST)).containsExactlyInAnyOrder(
                "ingestion.channels.write",
                "control-totals.write",
            )
            val iat =
                token.jwtClaimsSet.issueTime
                    .toInstant()
                    .epochSecond
            assertThat(iat).isEqualTo(epoch(now))
            assertThat(
                token.jwtClaimsSet.expirationTime
                    .toInstant()
                    .epochSecond,
            ).isEqualTo(iat + first.expiresIn!!)
            assertThat(first.expiresIn).isEqualTo(900)
            assertThat(claims["jti"]).isEqualTo(first.tokenId)
            val second = t.fresh(ingest)
            assertThat(second.tokenId).isNotEqualTo(first.tokenId)
            assertThat(claimFindings(claimsOf(second.accessToken!!), expected, setOf(first.tokenId!!))).isEmpty()
        }

    @Test
    fun `INV-AC-8848 the monitoring and the filing-workflow principals get one scope and their own permissions`() =
        run { t ->
            t.kit.clock.freeze()
            val monitoring = t.principal("MONITORING", "installation")
            val monitored = claimsOf(t.fresh(monitoring).accessToken!!)
            assertThat(monitored["scope"]).isEqualTo("monitoring")
            assertThat(monitored["principal_scope"]).isEqualTo("installation")
            assertThat(monitored).doesNotContainKey("tenant_id")

            val workflow = t.principal("FILING_WORKFLOW", "tenant:${t.kit.tenant}")
            val flowClaims = claimsOf(t.fresh(workflow).accessToken!!)
            assertThat(flowClaims["scope"]).isEqualTo("filing-workflow")
            assertThat(flowClaims["permissions"] as Collection<*>).doesNotContain("ingestion.channels.write")
            listOf(monitored, flowClaims).forEach { assertThat(scopeMembers(it)).hasSize(1) }
        }

    @Test
    fun `INV-AC-8848 probes a second scope member a missing audience and a repeated jti each fail the claim check`() =
        run { t ->
            t.kit.clock.freeze()
            t.kit.entity("ENT-1")
            val ingest = t.principal("INGEST", "entity:ENT-1")
            val first = t.fresh(ingest)
            val claims = claimsOf(first.accessToken!!)
            val expected =
                Expected(
                    ingest.ref,
                    "ingest",
                    "entity:ENT-1",
                    t.kit.tenant.toString(),
                    permissionsOf(PrincipalType.INGEST),
                )
            assertThat(claimFindings(claims, expected)).isEmpty()
            val twoScopes = claims + ("scope" to "ingest reporting")
            assertThat(claimFindings(twoScopes, expected)).containsExactly("scope")
            val noAudience = claims + ("aud" to listOf("https://other.invalid"))
            assertThat(claimFindings(noAudience, expected)).containsExactly("aud")
            // a second call that returns a jti already seen is the failure; here the seen set holds the new jti
            val second = t.fresh(ingest)
            val repeated =
                claimFindings(claimsOf(second.accessToken!!), expected, setOf(first.tokenId!!, second.tokenId!!))
            assertThat(repeated).containsExactly("jti")
        }

    @Test
    fun `INV-AC-8848 the signature verifies with the published key of the header kid and the five checks pass`() =
        run { t ->
            val now = t.kit.clock.freeze()
            val ingest = t.principal("INGEST", "tenant:${t.kit.tenant}")
            val issued = t.fresh(ingest)
            val published = t.published()
            assertThat(t.verify(issued.accessToken!!, published, now)).isEmpty()

            // probes
            val other = TestSecrets.newSigningKey().toECPublicKey().encoded
            assertThat(t.verify(issued.accessToken!!, published, now, useKey = other)).contains("signature")
            val parts = issued.accessToken!!.split('.')
            val payload = Base64.getUrlDecoder().decode(parts[1])
            val at = String(payload).indexOf("PRN-") + 4
            payload[at] = (payload[at].toInt() xor 1).toByte()
            val flipped = "${parts[0]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}.${parts[2]}"
            assertThat(t.verify(flipped, published, now)).contains("signature")
            val expiry =
                SignedJWT
                    .parse(issued.accessToken!!)
                    .jwtClaimsSet.expirationTime
                    .toInstant()
            assertThat(t.verify(issued.accessToken!!, published, expiry.plusSeconds(1))).contains("exp")
        }

    @Test
    fun `INV-AC-8848 the lifetime default 900 a configured 600 and values outside 1 to 900 stop the start-up`() {
        run { t ->
            val ingest = t.principal("INGEST", "tenant:${t.kit.tenant}")
            assertThat(t.fresh(ingest).expiresIn).isEqualTo(900)
        }
        run("access.token.lifetime-seconds=900") { t ->
            assertThat(t.fresh(t.principal("INGEST", "tenant:${t.kit.tenant}")).expiresIn).isEqualTo(900)
        }
        run("access.token.lifetime-seconds=600") { t ->
            t.kit.clock.freeze()
            val issued = t.fresh(t.principal("INGEST", "tenant:${t.kit.tenant}"))
            assertThat(issued.expiresIn).isEqualTo(600)
            val jwt = SignedJWT.parse(issued.accessToken!!).jwtClaimsSet
            assertThat(Duration.between(jwt.issueTime.toInstant(), jwt.expirationTime.toInstant()).seconds)
                .isEqualTo(600)
        }
        listOf("901", "0", "-1").forEach { value ->
            val mounted = TestSecrets.mount()
            BoundaryFixture
                .runner(mounted = mounted)
                .withPropertyValues("access.token.lifetime-seconds=$value")
                .run { context ->
                    val failure = context.startupFailure
                    assertThat(failure).isNotNull
                    assertThat(generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() })
                        .contains("access.token.lifetime-seconds")
                }
        }
    }

    @Test
    fun `INV-AC-8848 the service refuses to start without a key rotation interval`() {
        val without = TestSecrets.TOKEN_PROPERTIES.filterNot { it.startsWith("access.token.key-rotation-interval") }
        BoundaryFixture
            .runner(mounted = TestSecrets.mount(), tokenProperties = without.toTypedArray())
            .run { context ->
                val failure = context.startupFailure
                assertThat(failure).isNotNull
                assertThat(generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() })
                    .contains("ACCESS_TOKEN_KEY_ROTATION_INTERVAL")
            }
    }

    @Test
    fun `INV-AC-8848 the signing key appears in no response no log line and no holder text`() =
        run { t ->
            t.kit.clock.freeze()
            val key = ecKeyOfMount(t.mounted)
            val needles = TokenKit.needlesOf(key)
            val ingest = t.principal("INGEST", "tenant:${t.kit.tenant}")
            val created = t.kit.create("INGEST")
            val issued = t.fresh(ingest)
            val bodies =
                listOf(
                    mapper.writeValueAsString(issued),
                    mapper.writeValueAsString(t.keys.getVerificationKeys().body),
                    mapper.writeValueAsString(created),
                )
            assertThat(t.leaks(bodies, needles)).isFalse()
            assertThat(t.leaks(t.logLines(), needles)).isFalse()
            val holder = t.kit.context.getBean(com.dynokore.gst.access.application.token.AccessTokenSigner::class.java)
            assertThat(t.leaks(listOf(holder.open().toString()), needles)).isFalse()
            val der = t.published().map { it.publicKey }
            assertThat(der).isNotEmpty
            der.forEach { assertThat(TokenKit.isPublicOnly(it)).isTrue() }
            BoundaryFixture.asSuperuser { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT public_key FROM access.verification_key").use { rows ->
                        while (rows.next()) {
                            val column = Base64.getDecoder().decode(rows.getString(1))
                            assertThat(TokenKit.isPublicOnly(column)).isTrue()
                        }
                    }
                }
            }

            // probes: a logger that prints the key, and a body that holds the PKCS#8 bytes, are both caught
            assertThat(t.leaks(listOf("signing key ${needles[0]} loaded"), needles)).isTrue()
            assertThat(t.leaks(listOf("""{"keys":[{"publicKey":"${needles[0]}"}]}"""), needles)).isTrue()
        }

    @Test
    fun `INV-AC-8848 with the secret unreadable no token is issued 503 and the signer signs 0 times`() =
        run { t ->
            t.kit.clock.freeze()
            val ingest = t.principal("INGEST", "tenant:${t.kit.tenant}")
            t.removeKey()
            val before = t.calls.signs.get()
            val refusal = PrincipalKit.refusal { t.fresh(ingest) }
            assertThat(refusal).contains("SRT-011")
            assertThat(t.calls.signs.get()).isEqualTo(before)
            assertThat(t.published()).isEmpty()
        }

    @Test
    fun `INV-AC-8848 rotation publishes the old and the new key together and drops the old key after its window`() =
        run { t ->
            val t0 = t.kit.clock.freeze()
            val k1 = ecKeyOfMount(t.mounted)
            val ingest = t.principal("INGEST", "tenant:${t.kit.tenant}")

            // (a) at t0 with K1
            val a = t.fresh(ingest)
            val kid1 = a.keyId!!
            assertThat(t.published().map { it.kid }).containsExactly(kid1)
            assertThat(kid1).hasSize(16).matches("[0-9a-f]{16}")
            assertThat(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(k1.toECPublicKey().encoded)
                    .take(8)
                    .joinToString("") { "%02x".format(Locale.ROOT, it) },
            ).isEqualTo(kid1)

            // (b) the secret is replaced by K2 and the clock reaches t1
            val interval = Duration.parse(TestSecrets.ROTATION_INTERVAL)
            t.replaceKey(TestSecrets.newSigningKey())
            t.kit.clock.advance(interval)
            val t1 = t0.plus(interval)
            val b = t.fresh(ingest)
            val kid2 = b.keyId!!
            assertThat(kid2).isNotEqualTo(kid1)
            val both = t.published()
            assertThat(both.map { it.kid }).containsExactlyInAnyOrder(kid1, kid2)
            assertThat(t.verify(a.accessToken!!, both, t0.plusSeconds(1))).isEmpty()
            assertThat(t.verify(b.accessToken!!, both, t1)).isEmpty()
            val keyOne = both.single { it.kid == kid1 }.publicKey
            assertThat(t.verify(b.accessToken!!, both, t1, useKey = keyOne)).contains("signature")

            // (c) at t2 = t1 plus the lifetime plus one second only K2 is published
            t.kit.clock.advance(Duration.ofSeconds(900 + 1))
            assertThat(t.published().map { it.kid }).containsExactly(kid2)
        }

    @Test
    fun `INV-AC-8848 with the secret not replaced a call past the signing interval answers 503 and signs 0 times`() =
        run { t ->
            t.kit.clock.freeze()
            val ingest = t.principal("INGEST", "tenant:${t.kit.tenant}")
            assertThat(t.fresh(ingest).decision).isEqualTo(Decision.ISSUED)
            t.kit.clock.advance(Duration.parse(TestSecrets.ROTATION_INTERVAL))
            val before = t.calls.signs.get()
            assertThat(PrincipalKit.refusal { t.fresh(ingest) }).contains("SRT-011")
            assertThat(t.calls.signs.get()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8848 a revoked principal gets REFUSED with no token and the signer is not called`() =
        run { t ->
            val now = t.kit.clock.freeze()
            t.kit.entity("ENT-1")
            t.kit.entity("ENT-2")
            val revoked = t.principal("INGEST", "entity:ENT-1")
            val other = t.principal("INGEST", "entity:ENT-2")
            assertThat(t.fresh(revoked).decision).isEqualTo(Decision.ISSUED)
            t.kit.revoke(revoked.ref)
            val signsBefore = t.calls.signs.get()
            val refused = t.fresh(revoked)
            assertThat(refused.decision).isEqualTo(Decision.REFUSED)
            assertThat(refused.accessToken).isNull()
            assertThat(refused.keyId).isNull()
            assertThat(t.calls.signs.get()).isEqualTo(signsBefore)
            // the proof of one principal under the reference of the other is REFUSED, as in INV-T-4937
            assertThat(t.fresh(other).decision).isEqualTo(Decision.ISSUED)
            assertThat(t.issue(revoked.ref, other.proof).decision).isEqualTo(Decision.REFUSED)
            assertThat(t.issue(other.ref, revoked.proof).decision).isEqualTo(Decision.REFUSED)
            val entry =
                t.keys
                    .getRevocationList(null, 100, null)
                    .body!!
                    .items
                    .single { it.principalRef == revoked.ref }
            assertThat(Duration.between(entry.revokedAt, entry.expiresAt)).isEqualTo(Duration.ofSeconds(900))
            assertThat(entry.revokedAt.toInstant()).isEqualTo(now)
        }

    @Test
    fun `INV-AC-8848 the revocation entry lasts 900 seconds also when the configured lifetime is 600`() =
        run("access.token.lifetime-seconds=600") { t ->
            t.kit.clock.freeze()
            val revoked = t.principal("INGEST", "tenant:${t.kit.tenant}")
            t.kit.revoke(revoked.ref)
            val entry =
                t.keys
                    .getRevocationList(null, 100, null)
                    .body!!
                    .items
                    .single { it.principalRef == revoked.ref }
            assertThat(Duration.between(entry.revokedAt, entry.expiresAt)).isEqualTo(Duration.ofSeconds(900))
            assertThat(t.fresh(revoked).decision).isEqualTo(Decision.REFUSED)
        }

    @Test
    fun `INV-AC-8848 the token carries the tenant of the principal and an unknown reference signs 0 times`() =
        run { first ->
            first.kit.clock.freeze()
            val second = PrincipalKit(first.kit.context)
            val keyTwo = second.newKey("client-two")
            val created = second.create("INGEST", method = PrincipalKit.JWT, key = keyTwo)
            val issued = first.issue(created.principalRef, second.proof(keyTwo, created.principalRef))
            assertThat(issued.decision).isEqualTo(Decision.ISSUED)
            val tenantClaim = claimsOf(issued.accessToken!!)["tenant_id"]
            assertThat(tenantClaim).isEqualTo(second.tenant.toString())
            assertThat(tenantClaim).isNotEqualTo(first.kit.tenant.toString())
            assertThat(tenantClaim).isNotEqualTo(TenantId.PLATFORM.toString())

            val signs = first.calls.signs.get()
            val unknown = first.issue("PRN-99999999", "proof")
            assertThat(unknown.decision).isEqualTo(Decision.REFUSED)
            assertThat(unknown.accessToken).isNull()
            assertThat(first.calls.signs.get()).isEqualTo(signs)
        }

    private fun ecKeyOfMount(mounted: Path) =
        com.nimbusds.jose.jwk.ECKey
            .parse(
                java.nio.file.Files
                    .readString(mounted.resolve("access/token-signing-key")),
            )
}
