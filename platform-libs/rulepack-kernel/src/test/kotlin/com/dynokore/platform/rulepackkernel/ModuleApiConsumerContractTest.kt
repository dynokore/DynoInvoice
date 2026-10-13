package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Consumer-driven contract test of the module API (done-when, T22's "contract
 * tests pass"): a fake consumer written the way `rules-svc`'s own use case
 * would be — constructor-injected against only [PackVerifier],
 * [PackContentValidator] and [PackRegistry], the three ports `rulepack-kernel-api`
 * declares — compiled and run against the real `-impl` implementations. The
 * same job `tenancy-kernel`'s own `ModuleApiConsumerContractTest` does for its
 * module: if verification, content validation or resolution ever drifts from
 * what a consumer is written to expect, this fails.
 */
@Tag("integration")
class ModuleApiConsumerContractTest {
    /** Written exactly as a `rules-svc` use case would be: only `-api` types in its constructor. */
    private class FakeConsumerUseCase(
        private val verifier: PackVerifier,
        private val contentValidator: PackContentValidator,
        private val registry: PackRegistry,
    ) {
        /** The distribution path (INV-REQ-1195, packs.yaml "Distribute"): verify, then validate, then stage. */
        fun stage(
            artefact: PackArtefact,
            trustAnchors: TrustAnchors,
            tenant: TenantId? = null,
        ): PackVersion {
            val outcome = verifier.verify(artefact, trustAnchors)
            check(outcome is PackVerificationOutcome.Verified) {
                "expected a verified artefact in this fixture: $outcome"
            }

            val contentOutcome = contentValidator.validate(artefact.contents)
            check(contentOutcome is ContentValidationOutcome.Accepted) {
                "expected declarative content in this fixture: $contentOutcome"
            }

            val version = PackVersion(outcome.manifest, tenant)
            registry.register(version)
            return version
        }

        fun resolve(
            packType: PackType,
            tenant: TenantId?,
            resolutionDate: LocalDate,
        ): PackVersion? = registry.resolve(packType, tenant, resolutionDate)
    }

    @Test
    fun `a verified, validated artefact can be staged and then resolved by its business date`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))

        val useCase =
            FakeConsumerUseCase(
                verifier = BouncyCastlePackVerifier(),
                contentValidator = JacksonPackContentValidator(),
                registry = InMemoryPackRegistry(),
            )

        val artefact =
            PackFixtures.artefact(
                manifest = PackFixtures.manifestJson(effectiveFrom = "2026-10-01"),
                keyPair = keyPair,
                certificate = certificate,
            )

        val staged = useCase.stage(artefact, trustAnchors)
        assertEquals(PackType("code-catalogue"), staged.packType)

        assertEquals(staged, useCase.resolve(PackType("code-catalogue"), null, LocalDate.of(2026, 10, 15)))
        assertNull(useCase.resolve(PackType("code-catalogue"), null, LocalDate.of(2026, 9, 30)))
    }

    @Test
    fun `an artefact whose content carries a script is never staged, even though it verifies`() {
        val keyPair = PackFixtures.signingKeyPair()
        val certificate = PackFixtures.selfSignedCertificate(keyPair, "2026-train-primary")
        val trustAnchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))

        val useCase =
            FakeConsumerUseCase(
                verifier = BouncyCastlePackVerifier(),
                contentValidator = JacksonPackContentValidator(),
                registry = InMemoryPackRegistry(),
            )

        val executableContents = """{"handlers":[{"name":"self-billing","script":"eval(payload)"}]}""".toByteArray()
        val artefact =
            PackFixtures.artefact(contents = executableContents, keyPair = keyPair, certificate = certificate)

        val failure = assertThrows(IllegalStateException::class.java) { useCase.stage(artefact, trustAnchors) }
        assert(failure.message?.contains("declarative content") == true)
    }
}
