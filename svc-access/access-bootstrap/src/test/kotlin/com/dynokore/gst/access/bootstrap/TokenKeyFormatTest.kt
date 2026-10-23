package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.IssueAccessTokenResponse.Decision
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64

/**
 * The key format of the signing secret (INV-Q-9325 A, INV-T-8850): one private P-256 JWK. Anything else gives 503
 * SRT-011, the signer completes 0 times, and neither the refusal nor a log line holds the secret value.
 */
class TokenKeyFormatTest {
    private fun run(block: (TokenKit) -> Unit) {
        BoundaryFixture.deleteVerificationKeys()
        val mounted = BoundaryFixture.mount(TestSecrets.newSigningKey())
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

    private fun TokenKit.subject(): Pair<String, String> {
        val key = kit.newKey("client-key-" + java.util.UUID.randomUUID())
        val created = kit.create("INGEST", scope = "tenant:${kit.tenant}", method = PrincipalKit.JWT, key = key)
        return created.principalRef to kit.proof(key, created.principalRef)
    }

    private fun TokenKit.setSecret(text: String) = Files.writeString(mounted.resolve("access/token-signing-key"), text)

    private fun pem(key: ECKey): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.toECPrivateKey().encoded) +
            "\n-----END PRIVATE KEY-----\n"

    @Test
    fun `INV-AC-8848 the valid P-256 JWK issues and a kid member of the JWK is ignored`() =
        run { t ->
            t.kit.clock.freeze()
            val (ref, proof) = t.subject()
            val key = ECKey.parse(Files.readString(t.mounted.resolve("access/token-signing-key")))
            val plain = t.issue(ref, proof)
            assertThat(plain.decision).isEqualTo(Decision.ISSUED)
            val derived = SignedJWT.parse(plain.accessToken).header.keyID
            assertThat(derived).hasSize(KID_LENGTH)
            t.setSecret(
                ECKey
                    .Builder(key)
                    .keyID("other")
                    .build()
                    .toJSONString(),
            )
            val withKid = t.issue(ref, proof)
            assertThat(withKid.decision).isEqualTo(Decision.ISSUED)
            assertThat(SignedJWT.parse(withKid.accessToken).header.keyID).isEqualTo(derived).isNotEqualTo("other")
        }

    @Test
    fun `INV-AC-8848 a secret that is not one private P-256 JWK gives 503 SRT-011 and the signer completes 0 times`() =
        run { t ->
            t.kit.clock.freeze()
            val (ref, proof) = t.subject()
            val good = ECKey.parse(Files.readString(t.mounted.resolve("access/token-signing-key")))
            val values =
                mapOf(
                    "PKCS8 base64" to Base64.getEncoder().encodeToString(good.toECPrivateKey().encoded),
                    "PEM text" to pem(good),
                    "P-384 JWK" to ECKeyGenerator(Curve.P_384).generate().toJSONString(),
                    // the JDK has no secp256k1 generator: the members of a P-256 key under the curve name secp256k1
                    "secp256k1 JWK" to good.toJSONString().replace("\"P-256\"", "\"secp256k1\""),
                    "JWK without d" to good.toPublicJWK().toJSONString(),
                    "not json" to "not json",
                )
            values.forEach { (label, value) ->
                t.setSecret(value)
                val before = t.calls.signs.get()
                val refusal = PrincipalKit.refusal { t.issue(ref, proof) }
                assertThat(refusal).describedAs(label).contains("SRT-011")
                assertThat(t.calls.signs.get()).describedAs(label).isEqualTo(before)
                assertThat(refusal).describedAs(label).doesNotContain(value)
                assertThat(t.leaks(t.logLines(), listOf(value))).describedAs(label).isFalse()
            }
            assertThat(t.published()).isEmpty()
        }

    private companion object {
        const val KID_LENGTH = 16
    }
}
