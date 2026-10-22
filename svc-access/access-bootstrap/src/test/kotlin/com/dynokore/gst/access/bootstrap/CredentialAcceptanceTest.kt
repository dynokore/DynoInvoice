package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.PRINCIPAL_CREDENTIAL
import com.dynokore.gst.access.contract.model.AuthenticatePrincipalResponse.Decision
import com.dynokore.gst.access.contract.model.CreatePrincipalRequest
import com.dynokore.gst.access.contract.model.CreatePrincipalRequestPublicKeyJwk
import com.dynokore.gst.access.contract.model.CredentialInfo
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Credentials, rotation, withdrawal and authentication at the service boundary (INV-T-4937; INV-Q-8777 A), on the test
 * clock. The controllers are called under a bound tenant over a real PostgreSQL with forced row-level security.
 */
class CredentialAcceptanceTest {
    private val jwt = PrincipalKit.JWT
    private val authJwt = PrincipalKit.AUTH_JWT

    @Test
    fun `an ingest principal created with a PRIVATE_KEY_JWT credential authenticates with its proof`() =
        PrincipalKit.run { kit ->
            val key = kit.newKey("erp-1")
            val created = kit.create("INGEST", method = jwt, key = key)
            assertThat(created.credential!!.status).isEqualTo(CredentialInfo.Status.ACTIVE)
            assertThat(created.credential!!.method).isEqualTo(CredentialInfo.Method.PRIVATE_KEY_JWT)
            val decision = kit.authenticate(created.principalRef, authJwt, kit.proof(key, created.principalRef))
            assertThat(decision.decision).isEqualTo(Decision.AUTHENTICATED)
            assertThat(decision.principalRef).isEqualTo(created.principalRef)
            assertThat(decision.principalType).isEqualTo("INGEST")
            assertThat(decision.credentialRef).isEqualTo(created.credential!!.credentialRef)
        }

    @Test
    fun `INV-AC-2044 and INV-AC-8617 both credentials authenticate in the overlap window with no failed call`() =
        PrincipalKit.run { kit ->
            val oldKey = kit.newKey("erp-1")
            val newKey = kit.newKey("erp-2")
            val created = kit.create("INGEST", method = jwt, key = oldKey)
            val ref = created.principalRef
            val failures = mutableListOf<String>()

            fun call(
                key: com.nimbusds.jose.jwk.ECKey,
                label: String,
            ) {
                val answer = kit.authenticate(ref, authJwt, kit.proof(key, ref))
                if (answer.decision != Decision.AUTHENTICATED) failures += label
            }
            call(oldKey, "old before rotation")
            val rotatedAt = kit.clock.freeze()
            val rotated =
                kit.rotate(
                    ref,
                    60,
                    PrincipalKit.ROTATE_JWT,
                    newKey,
                )
            assertThat(rotated.credential).isNotNull()
            assertThat(rotated.previousCredential!!.credentialRef).isEqualTo(created.credential!!.credentialRef)
            assertThat(
                rotated.previousCredential!!.validUntil!!.toInstant(),
            ).isEqualTo(rotatedAt.plus(Duration.ofMinutes(60)))
            call(oldKey, "old right after rotation")
            call(newKey, "new right after rotation")
            kit.clock.advance(Duration.ofMinutes(30))
            call(oldKey, "old at plus 30 minutes")
            call(newKey, "new at plus 30 minutes")
            assertThat(failures).describedAs("failed calls between rotation and withdrawal").isEmpty()
            // after withdrawal the old one is refused and the new one is admitted
            kit.withdraw(ref, created.credential!!.credentialRef)
            assertThat(kit.authenticate(ref, authJwt, kit.proof(oldKey, ref)).decision).isEqualTo(Decision.REFUSED)
            assertThat(
                kit.authenticate(ref, authJwt, kit.proof(newKey, ref)).decision,
            ).isEqualTo(Decision.AUTHENTICATED)
        }

    @Test
    fun `INV-AC-2044 the old proof is refused at plus 61 minutes without withdrawal, a repeat withdrawal is a no-op`() =
        PrincipalKit.run { kit ->
            val oldKey = kit.newKey("erp-1")
            val newKey = kit.newKey("erp-2")
            val created = kit.create("INGEST", method = jwt, key = oldKey)
            val ref = created.principalRef
            kit.clock.freeze()
            kit.rotate(ref, 60, PrincipalKit.ROTATE_JWT, newKey)
            kit.clock.advance(Duration.ofMinutes(61))
            // the proofs are signed at plus 61 minutes, so only the end of the credential can refuse the old one
            assertThat(kit.authenticate(ref, authJwt, kit.proof(oldKey, ref)).decision).isEqualTo(Decision.REFUSED)
            assertThat(
                kit.authenticate(ref, authJwt, kit.proof(newKey, ref)).decision,
            ).isEqualTo(Decision.AUTHENTICATED)
            val first = kit.withdraw(ref, created.credential!!.credentialRef)
            val second = kit.withdraw(ref, created.credential!!.credentialRef)
            assertThat(first.credential!!.status).isEqualTo(CredentialInfo.Status.WITHDRAWN)
            assertThat(second).isEqualTo(first)
            assertThat(
                kit.authenticate(ref, authJwt, kit.proof(newKey, ref)).decision,
            ).isEqualTo(Decision.AUTHENTICATED)
        }

    @Test
    fun `INV-AC-936 a revoked service principal is refused at once with every credential`() =
        PrincipalKit.run { kit ->
            kit.entity("ENT-1")
            val oldKey = kit.newKey("erp-1")
            val newKey = kit.newKey("erp-2")
            val created = kit.create("INGEST", scope = "entity:ENT-1", method = jwt, key = oldKey)
            val ref = created.principalRef
            kit.rotate(ref, 60, PrincipalKit.ROTATE_JWT, newKey)
            assertThat(
                kit.authenticate(ref, authJwt, kit.proof(newKey, ref)).decision,
            ).isEqualTo(Decision.AUTHENTICATED)
            kit.revoke(ref)
            listOf(oldKey, newKey).forEach {
                val answer = kit.authenticate(ref, authJwt, kit.proof(it, ref))
                assertThat(answer.decision).isEqualTo(Decision.REFUSED)
                assertThat(answer.principalRef).isNull()
            }
            assertThat(kit.decide(ref, "ingestion.channels.write", "entity:ENT-1")).isEqualTo("DENY")
            // an unknown principal is REFUSED with a 200 answer, not a 404
            val unknown = kit.authenticate("PRN-0000000000000000", authJwt, kit.proof(newKey, "PRN-0000000000000000"))
            assertThat(unknown.decision).isEqualTo(Decision.REFUSED)
        }

    @Test
    fun `the proof of one principal presented with the reference of another is refused`() =
        PrincipalKit.run { kit ->
            val keyA = kit.newKey("a")
            val keyB = kit.newKey("b")
            val a = kit.create("INGEST", scope = "entity:ENT-1", method = jwt, key = keyA)
            val b = kit.create("INGEST", scope = "entity:ENT-2", method = jwt, key = keyB)
            assertThat(kit.authenticate(a.principalRef, authJwt, kit.proof(keyA, a.principalRef)).decision)
                .isEqualTo(Decision.AUTHENTICATED)
            // the proof of A with the reference of B, and an assertion of A signed by B's key for B's reference
            assertThat(kit.authenticate(b.principalRef, authJwt, kit.proof(keyA, a.principalRef)).decision)
                .isEqualTo(Decision.REFUSED)
            assertThat(kit.authenticate(b.principalRef, authJwt, kit.proof(keyA, b.principalRef)).decision)
                .isEqualTo(Decision.REFUSED)
            val secretA = kit.create("REPORTING", scope = "entity:ENT-1", method = PrincipalKit.SECRET)
            val secretB = kit.create("REPORTING", scope = "entity:ENT-2", method = PrincipalKit.SECRET)
            val proofA = secretA.credential!!.clientSecret!!
            assertThat(kit.authenticate(secretA.principalRef, PrincipalKit.AUTH_SECRET, proofA).decision)
                .isEqualTo(Decision.AUTHENTICATED)
            assertThat(kit.authenticate(secretB.principalRef, PrincipalKit.AUTH_SECRET, proofA).decision)
                .isEqualTo(Decision.REFUSED)
            // a method the principal has no credential for
            assertThat(kit.authenticate(secretA.principalRef, PrincipalKit.AUTH_MTLS, proofA).decision)
                .isEqualTo(Decision.REFUSED)
        }

    @Test
    fun `an ap-callback principal authenticates by mutual TLS and a tampered or expired assertion is refused`() =
        PrincipalKit.run { kit ->
            val ap =
                kit.create(
                    "AP_CALLBACK",
                    scope = "entity:ENT-1",
                    method = PrincipalKit.MTLS,
                    thumbprint = "ab12cd34",
                )
            assertThat(kit.authenticate(ap.principalRef, PrincipalKit.AUTH_MTLS, "ab12cd34").decision)
                .isEqualTo(Decision.AUTHENTICATED)
            assertThat(kit.authenticate(ap.principalRef, PrincipalKit.AUTH_MTLS, "ab12cd35").decision)
                .isEqualTo(Decision.REFUSED)
            val key = kit.newKey("k")
            val jwtPrincipal = kit.create("INGEST", scope = "entity:ENT-1", method = jwt, key = key)
            val ref = jwtPrincipal.principalRef
            val expired = kit.proof(key, ref, Duration.ofSeconds(-5))
            assertThat(kit.authenticate(ref, authJwt, expired).decision).isEqualTo(Decision.REFUSED)
            val good = kit.proof(key, ref)
            val tampered = good.substring(0, good.length - 4) + (if (good.endsWith("AAAA")) "BBBB" else "AAAA")
            assertThat(kit.authenticate(ref, authJwt, tampered).decision).isEqualTo(Decision.REFUSED)
            assertThat(kit.authenticate(ref, authJwt, "not-a-jwt").decision).isEqualTo(Decision.REFUSED)
        }

    @Test
    fun `an invalid credential request is 400 and creates no principal`() =
        PrincipalKit.run { kit ->
            val key = kit.newKey("k")

            fun refused(block: () -> Any?) = assertThat(PrincipalKit.refusal(block)).contains("SRT-001")
            refused { kit.create("INGEST", method = jwt) }
            refused { kit.create("INGEST", method = PrincipalKit.MTLS) }
            refused { kit.create("INGEST", method = PrincipalKit.SECRET, key = key) }
            refused { kit.create("INGEST", method = jwt, key = key, thumbprint = "ab") }
            refused { kit.create("INGEST", key = key) }
            refused { kit.create("INGEST", thumbprint = "ab") }
            refused {
                kit.asAdmin {
                    kit.principals.createPrincipal(
                        UUID.randomUUID(),
                        CreatePrincipalRequest(
                            CreatePrincipalRequest.PrincipalType.INGEST,
                            "tenant:${kit.tenant}",
                            credentialMethod = jwt,
                            publicKeyJwk = CreatePrincipalRequestPublicKeyJwk(kty = "EC", crv = "P-256"),
                        ),
                    )
                }
            }
            assertThat(kit.principalCount()).isZero()
        }

    @Test
    fun `clientSecret appears in the response that creates or rotates and in no later response`() =
        PrincipalKit.run { kit ->
            val created = kit.create("REPORTING", scope = "entity:ENT-1", method = PrincipalKit.SECRET)
            val secret = created.credential!!.clientSecret!!
            assertThat(secret).hasSizeGreaterThan(30)
            val rotated =
                kit.rotate(
                    created.principalRef,
                    10,
                    PrincipalKit.ROTATE_SECRET,
                )
            val newSecret = rotated.credential!!.clientSecret!!
            assertThat(newSecret).isNotEqualTo(secret)
            assertThat(rotated.previousCredential!!.clientSecret).isNull()
            val later =
                listOf(
                    kit.withdraw(created.principalRef, created.credential!!.credentialRef),
                    kit.create("REPORTING", scope = "entity:ENT-1"),
                    kit.revoke(created.principalRef),
                )
            later.forEach { assertThat(it.credential?.clientSecret).isNull() }
            // only a verifier is stored, never the secret
            val stored =
                kit.asAdmin {
                    kit.context.getBean(TransactionRunner::class.java).inTransaction {
                        kit.context
                            .getBean(DSLContext::class.java)
                            .select(PRINCIPAL_CREDENTIAL.SECRET_VERIFIER)
                            .from(PRINCIPAL_CREDENTIAL)
                            .fetch(PRINCIPAL_CREDENTIAL.SECRET_VERIFIER)
                            .filterNotNull()
                    }
                }
            assertThat(stored).hasSize(2).doesNotContain(secret, newSecret)
        }
}
