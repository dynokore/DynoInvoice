package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.token.AccessTokenClaims
import com.dynokore.gst.access.application.token.AccessTokenSigner
import com.dynokore.gst.access.application.token.CertificationPolicy
import com.dynokore.gst.access.application.token.IssueAccessTokenUseCase
import com.dynokore.gst.access.application.token.SigningSession
import com.dynokore.gst.access.application.token.TenantEnvironment
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.gst.access.application.token.VerificationKeyRegistry
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * INV-AC-11813: the certification policy (INV-Q-9032 A) and the environment claim of the access token (INV-Q-11811 A),
 * INV-REQ-584. The claims are captured at the signing port.
 */
class TenantEnvironmentPolicyTest {
    private val environments = listOf(TenantEnvironment.SANDBOX, TenantEnvironment.PRODUCTION, null)

    @Test
    fun `INV-AC-11813 permits answers the table of 18 cells by equality`() {
        val table =
            mapOf(PrincipalType.CERTIFICATION to listOf(true, false, false)) +
                PrincipalType.entries
                    .filter { it != PrincipalType.CERTIFICATION }
                    .associateWith { listOf(true, true, true) }
        assertThat(table.keys).containsExactlyInAnyOrder(*PrincipalType.entries.toTypedArray())
        var cells = 0
        table.forEach { (type, row) ->
            environments.forEachIndexed { i, env ->
                assertThat(CertificationPolicy.permits(type, env)).describedAs("$type $env").isEqualTo(row[i])
                cells++
            }
        }
        assertThat(cells).isEqualTo(18)
    }

    @Test
    fun `INV-AC-11813 probe permits is false for CERTIFICATION with null`() {
        assertThat(CertificationPolicy.permits(PrincipalType.CERTIFICATION, null)).isFalse()
    }

    @Test
    fun `INV-AC-11813 probe the member names and their order are pinned so an ordinal comparison cannot pass`() {
        assertThat(TenantEnvironment.entries.map { it.name }).containsExactly("PRODUCTION", "SANDBOX")
        assertThat(PrincipalType.entries.map { it.name })
            .containsExactly("INGEST", "FILING_WORKFLOW", "AP_CALLBACK", "REPORTING", "MONITORING", "CERTIFICATION")
    }

    private class CapturingSigner : AccessTokenSigner {
        val claims = mutableListOf<AccessTokenClaims>()

        override fun open(): SigningSession =
            object : SigningSession {
                override val kid = "k1"
                override val alg = "EdDSA"
                override val publicKey = "cHVi"

                override fun sign(claims: AccessTokenClaims): String {
                    this@CapturingSigner.claims += claims
                    return "t"
                }
            }
    }

    private fun useCase(signer: CapturingSigner) =
        IssueAccessTokenUseCase(
            signer = signer,
            registry = VerificationKeyRegistry { it },
            sets = MachinePermissionSets.STANDARD,
            settings = TokenSettings("iss", "aud", Duration.ofSeconds(900), Duration.ofDays(30)),
            clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC),
        )

    private val principal =
        ServicePrincipal("p1", TenantRef("t1"), PrincipalType.CERTIFICATION, "s", null, PrincipalStatus.ACTIVE, 1)

    @Test
    fun `INV-AC-11813 the claims handed to the signer carry the given environment and null when none is given`() {
        val signer = CapturingSigner()
        val useCase = useCase(signer)
        useCase.issue(principal, TenantEnvironment.SANDBOX)
        useCase.issue(principal, TenantEnvironment.PRODUCTION)
        useCase.issue(principal, null)
        useCase.issue(principal)
        assertThat(signer.claims.map { it.environment })
            .containsExactly(TenantEnvironment.SANDBOX, TenantEnvironment.PRODUCTION, null, null)
    }

    @Test
    fun `INV-AC-11813 probe the use case applies no default and reads no TenantEnvironments`() {
        val signer = CapturingSigner()
        useCase(signer).issue(principal, null)
        assertThat(signer.claims.single().environment).isNull()
        val types = IssueAccessTokenUseCase::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
        assertThat(types.map { it.simpleName }).doesNotContain("TenantEnvironments")
    }
}
