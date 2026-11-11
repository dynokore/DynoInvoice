package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.operatorsession.OperatorKeyPublication
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.domain.revocation.KeyUse
import com.dynokore.gst.access.domain.revocation.VerificationKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * INV-T-9156 (INV-Q-9028 A, INV-AC-9147): the machine signing public keys are published with `use` MACHINE_ACCESS in
 * OperatorSessionKeysChanged, in the transaction that stores the row, before the first token the key signs. The real
 * context over a real PostgreSQL, the gateway-side controller called directly, a clock the tests move.
 */
class MachineKeyPublicationTest {
    private val mapper = JsonMapper.builder().build()

    private fun run(block: (TokenKit) -> Unit) {
        BoundaryFixture.deleteVerificationKeys()
        val key = TestSecrets.newSigningKey()
        val mounted = BoundaryFixture.mount(key)
        BoundaryFixture.runner(mounted = mounted).run { context ->
            context.startupFailure?.let { throw it }
            val tokens = TokenKit(PrincipalKit(context), mounted, context.getBean(SignerCalls::class.java))
            try {
                block(tokens)
            } finally {
                tokens.close()
            }
        }
    }

    private fun events(): List<JsonNode> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select data::text from access.outbox where type like '%OperatorSessionKeysChanged%' " +
                            "order by seq",
                    ).use { rs ->
                        buildList { while (rs.next()) add(mapper.readTree(rs.getString(1))) }
                    }
            }
        }

    private fun storedWindow(kid: String): Pair<Instant, Instant> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select valid_from, valid_until from access.verification_key where kid = ?").use { st ->
                st.setString(1, kid)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant() to
                        rs.getObject(2, java.time.OffsetDateTime::class.java).toInstant()
                }
            }
        }

    private fun JsonNode.items(): List<JsonNode> = this["keys"].toList()

    private fun TokenKit.subject(): Pair<String, String> {
        val key = kit.newKey("client-key-" + java.util.UUID.randomUUID())
        val created = kit.create("INGEST", scope = "tenant:${kit.tenant}", method = PrincipalKit.JWT, key = key)
        return created.principalRef to kit.proof(key, created.principalRef)
    }

    private fun publication(t: TokenKit) = t.kit.context.getBean(OperatorKeyPublication::class.java)

    private fun candidate(
        kid: String,
        use: KeyUse,
        at: Instant,
    ) = VerificationKey(
        kid,
        "ES256",
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-$kid",
        at,
        at.plus(Duration.ofHours(2)),
        use,
    )

    @Test
    fun `INV-AC-9147 the event holds the machine key with use MACHINE_ACCESS equal to the stored key`() =
        run { t ->
            t.kit.clock.freeze()
            val before = events().size
            val (ref, proof) = t.subject()
            val issued = t.issue(ref, proof)
            val sent = events().drop(before)
            assertThat(sent).hasSize(1)
            val items = sent.single().items()
            val machine = items.single { it["kid"].asString() == issued.keyId }
            assertThat(machine["use"].asString()).isEqualTo("MACHINE_ACCESS")
            val body = t.published().single { it.kid == issued.keyId }
            assertThat(machine["alg"].asString()).isEqualTo(body.alg)
            assertThat(
                java.util.Base64
                    .getDecoder()
                    .decode(machine["publicKey"].asString()),
            ).isEqualTo(body.publicKey)
            val (from, until) = storedWindow(issued.keyId!!)
            assertThat(Instant.parse(machine["validFrom"].asString())).isEqualTo(from)
            assertThat(Instant.parse(machine["validUntil"].asString())).isEqualTo(until)
            assertThat(items.map { it["kid"].asString() }).doesNotHaveDuplicates()
            // no private key bytes in the payload or in the logs
            val needles = TokenKit.needlesOf(ecKey(t))
            assertThat(t.leaks(sent.map { it.toString() } + t.logLines(), needles)).isFalse()
            // a second token of the same key emits nothing more
            t.issue(ref, proof)
            assertThat(events().drop(before)).hasSize(1)
        }

    @Test
    fun `INV-AC-9147 the machine key is published before its first token, a rotation emits both keys`() =
        run { t ->
            val t0 = t.kit.clock.freeze()
            val before = events().size
            val (ref, proof) = t.subject()
            val first = t.issue(ref, proof)
            val firstEvent = events().drop(before).single()
            assertThat(firstEvent.items().map { it["kid"].asString() }).containsExactly(first.keyId)

            val interval = Duration.parse(TestSecrets.ROTATION_INTERVAL)
            t.replaceKey(TestSecrets.newSigningKey())
            t.kit.clock.advance(interval)
            val second = t.issue(ref, proof)
            val rotation = events().drop(before + 1)
            assertThat(rotation).hasSize(1)
            val items = rotation.single().items()
            assertThat(items.map { it["kid"].asString() }).containsExactlyInAnyOrder(first.keyId, second.keyId)
            assertThat(items.map { it["use"].asString() }.toSet()).containsExactly("MACHINE_ACCESS")
            // the old key stays published until validUntil of its row
            val old = items.single { it["kid"].asString() == first.keyId }
            assertThat(Instant.parse(old["validUntil"].asString())).isAfter(t0.plus(interval))

            // past the window of the old key the next publication drops it
            t.kit.clock.advance(Duration.ofSeconds(900 + 1))
            t.issue(ref, proof)
            val last = events().last().items().map { it["kid"].asString() }
            assertThat(last).containsExactly(second.keyId)
        }

    @Test
    fun `INV-AC-9147 probe a machine key published under OPERATOR_SESSION fails and emits nothing`() =
        run { t ->
            val now = t.kit.clock.freeze()
            val (ref, proof) = t.subject()
            val issued = t.issue(ref, proof)
            val before = events().size
            val asOperator =
                candidate(issued.keyId!!, KeyUse.OPERATOR_SESSION, now)
            assertThatThrownBy { publication(t).publish(listOf(asOperator)) }
                .isInstanceOf(SigningKeyUnavailable::class.java)
            assertThat(events()).hasSize(before)
            assertThat(t.published().map { it.kid }).containsExactly(issued.keyId)
        }

    @Test
    fun `INV-AC-9147 probe a kid shared by an operator key and a machine key fails the publication`() =
        run { t ->
            val now = t.kit.clock.freeze()
            val shared =
                "shared" +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(10)
            publication(t).publish(listOf(candidate(shared, KeyUse.OPERATOR_SESSION, now)))
            val before = events().size
            val lines = t.logLines().size
            assertThatThrownBy {
                publication(t).publish(listOf(candidate(shared, KeyUse.MACHINE_ACCESS, now)))
            }.isInstanceOf(SigningKeyUnavailable::class.java)
            assertThat(events()).hasSize(before)
            assertThat(t.logLines().drop(lines)).anyMatch { it.contains("key publication refused") && shared in it }
            assertThat(t.published().map { it.kid }).doesNotContain(shared)
        }

    private fun ecKey(t: TokenKit) =
        com.nimbusds.jose.jwk.ECKey.parse(
            java.nio.file.Files
                .readString(t.mounted.resolve("access/token-signing-key")),
        )
}
