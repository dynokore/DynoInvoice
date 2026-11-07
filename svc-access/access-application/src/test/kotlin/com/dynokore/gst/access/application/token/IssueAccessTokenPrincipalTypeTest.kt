package com.dynokore.gst.access.application.token

import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-13560 (issuing side), INV-REQ-584: the claim `principal_type` carries the kebab-case machine type. */
class IssueAccessTokenPrincipalTypeTest {
    private val claims = mutableListOf<AccessTokenClaims>()
    private var opened = 0

    private val signer =
        AccessTokenSigner {
            opened++
            object : SigningSession {
                override val kid = "k1"
                override val alg = "EdDSA"
                override val publicKey = "cHVi"

                override fun sign(claims: AccessTokenClaims): String {
                    this@IssueAccessTokenPrincipalTypeTest.claims += claims
                    return "t"
                }
            }
        }

    private val useCase =
        IssueAccessTokenUseCase(
            signer = signer,
            registry = VerificationKeyRegistry { it },
            sets = MachinePermissionSets.STANDARD,
            settings = TokenSettings("iss", "aud", Duration.ofSeconds(900), Duration.ofDays(30)),
            clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC),
        )

    private fun principal(type: PrincipalType) =
        ServicePrincipal(
            "p1",
            TenantRef("t1"),
            type,
            "s",
            "sys".takeIf { type == PrincipalType.FILING_WORKFLOW },
            PrincipalStatus.ACTIVE,
            1,
        )

    private fun issued(type: PrincipalType): String? {
        claims.clear()
        useCase.issue(principal(type))
        return claims.single().principalType
    }

    @Test
    fun `INV-AC-13560 ingest carries principalType ingest`() = assertEquals("ingest", issued(PrincipalType.INGEST))

    @Test
    fun `INV-AC-13560 filing workflow carries principalType filing-workflow`() =
        assertEquals("filing-workflow", issued(PrincipalType.FILING_WORKFLOW))

    @Test
    fun `INV-AC-13560 ap callback carries principalType ap-callback`() =
        assertEquals("ap-callback", issued(PrincipalType.AP_CALLBACK))

    @Test
    fun `INV-AC-13560 reporting carries principalType reporting`() =
        assertEquals("reporting", issued(PrincipalType.REPORTING))

    @Test
    fun `INV-AC-13560 monitoring carries principalType monitoring`() =
        assertEquals("monitoring", issued(PrincipalType.MONITORING))

    @Test
    fun `INV-AC-13560 certification carries principalType certification`() =
        assertEquals("certification", issued(PrincipalType.CERTIFICATION))

    @Test
    fun `INV-AC-13560 every stored type is mapped and the claim is never null`() {
        val all = PrincipalType.entries.map { issued(it) }
        all.forEach { assertNotNull(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `INV-AC-13560 probe each look-alike stored type is refused with a typed error`() {
        listOf(
            "filing_workflow",
            "sandbox-runner",
            "Filing-Workflow",
            "filing-workflow ",
            "principal-type",
            "ingest",
            "Ingest",
            "",
            " ",
        ).forEach { bad ->
            assertThrows(UnknownPrincipalType::class.java) { PrincipalTypeClaim.of(bad) }
        }
    }

    @Test
    fun `INV-AC-13560 probe a null stored type is refused`() {
        assertThrows(UnknownPrincipalType::class.java) { PrincipalTypeClaim.of(null) }
    }

    @Test
    fun `INV-AC-13560 the six stored names map one to one`() {
        assertEquals(
            listOf("ingest", "filing-workflow", "ap-callback", "reporting", "monitoring", "certification"),
            PrincipalType.entries.map { PrincipalTypeClaim.of(it.name) },
        )
    }

    @Test
    fun `INV-AC-13560 the type lookup runs before the signer is opened and the claims carry its result`() {
        useCase.issue(principal(PrincipalType.REPORTING))
        assertEquals(1, opened)
        assertEquals("reporting", claims.single().principalType)
    }
}
