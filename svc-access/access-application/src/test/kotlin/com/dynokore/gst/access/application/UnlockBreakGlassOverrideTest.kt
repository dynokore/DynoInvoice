package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassApproval
import com.dynokore.gst.access.application.breakglass.BreakGlassApprovals
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.BreakGlassSetting
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.application.idp.IdentityProviderProbe
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.gst.access.domain.breakglass.BreakGlassPairing
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** `unlockBreakGlass` while the identity provider counts as available (INV-Q-11557 A, INV-AC-9185). */
class UnlockBreakGlassOverrideTest {
    private val now = Instant.parse("2026-10-28T01:00:00Z")
    private val tenant = TenantRef("T-1")
    private val operator = IdentityRef("IDN-OP")
    private val approver = IdentityRef("IDN-AP")

    private val inserted = mutableListOf<Int>()
    private val alerts = mutableListOf<List<String?>>()
    private val audits = mutableListOf<GrantAuditEntry>()

    private fun grantOf(
        who: IdentityRef,
        role: Role,
    ) = Grant.issue(
        GrantHeader(GrantRef("G-${who.value}"), tenant, who),
        role,
        Scope.Tenant(tenant),
        ValidityWindow(Instant.parse("2026-01-01T00:00:00Z"), null),
        GrantState(GrantStatus.ACTIVE, GrantSource.BY_HAND, 0),
    )

    private val grants =
        mapOf(
            operator to grantOf(operator, Role.TENANT_ADMIN),
            approver to grantOf(approver, Role.TAX_APPROVER),
        )

    private fun available(up: Boolean): IdentityProviderAvailability {
        val availability = IdentityProviderAvailability("http://idp", IdentityProviderProbe { up }, 1)
        availability.probeOnce()
        return availability
    }

    private fun useCase(idpUp: Boolean) =
        UnlockBreakGlassUseCase(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            object : BreakGlassSessions {
                override fun insert(
                    sessionHours: Int,
                    unlockedAt: Instant,
                    approvedBy: List<String>,
                ): BreakGlassSession {
                    inserted += sessionHours
                    return BreakGlassSession(
                        "BG-1",
                        "T-1",
                        sessionHours,
                        unlockedAt,
                        unlockedAt.plusSeconds(1),
                        approvedBy,
                        false,
                    )
                }

                override fun find(ref: String): BreakGlassSession? = null

                override fun insertAction(
                    unlockRef: String,
                    action: BreakGlassAction,
                    target: String?,
                    callerService: String,
                ): String = error("not used")
            },
            fakeGrants(),
            { BreakGlassSetting(4, BreakGlassPairing.DEFAULT) },
            BreakGlassEffects(
                object : BreakGlassAlerts {
                    override fun raise(
                        code: String,
                        action: String,
                        unlockRef: String,
                    ) {
                        error("the unlock must use the form with the override reason")
                    }

                    override fun raise(
                        code: String,
                        action: String,
                        unlockRef: String,
                        overrideReason: String?,
                    ) {
                        alerts += listOf(code, action, unlockRef, overrideReason)
                    }
                },
                object : GrantAudit {
                    override fun record(entry: GrantAuditEntry) {
                        audits += entry
                    }
                },
            ),
            Clock.fixed(now, ZoneOffset.UTC),
            available(idpUp),
            object : BreakGlassApprovals {
                override fun replace(
                    approver: String,
                    idempotencyKey: java.util.UUID,
                    madeAt: Instant,
                    window: Duration,
                ): com.dynokore.gst.access.application.breakglass.StoredApproval = error("not used")

                // these tests are about the override: every named approver has an approval
                override fun consume(
                    approvers: Collection<String>,
                    after: Instant,
                    unlockRef: String,
                    consumedAt: Instant,
                ): Int = approvers.size
            },
            Duration.ofMinutes(15),
        )

    private fun fakeGrants(): GrantRepository =
        object : GrantRepository {
            override fun findByIdentity(identity: IdentityRef): List<Grant> = listOfNotNull(grants[identity])

            override fun find(ref: GrantRef): Grant? = error("not used")

            override fun findByRepeatKey(
                identity: IdentityRef,
                roleWire: String,
                scope: Scope,
            ): Grant? = error("not used")

            override fun insert(grant: Grant) = error("not used")

            override fun update(
                before: Grant,
                after: Grant,
            ) = error("not used")

            override fun lockIdentity(
                identity: IdentityRef,
                kind: GranteeKind,
            ) = error("not used")

            override fun nextAuthorityVersion(): Long = error("not used")

            override fun nextGrantRef(): GrantRef = error("not used")
        }

    private fun nothingWritten() = assertTrue(inserted.isEmpty() && alerts.isEmpty() && audits.isEmpty())

    @Test
    fun `available and no override reason is refused and writes nothing`() {
        assertEquals(
            UnlockBreakGlassOutcome.IdpAvailable,
            useCase(true).handle("U", tenant, listOf(operator, approver)),
        )
        nothingWritten()
    }

    @Test
    fun `lookalike override reasons never count as the override`() {
        listOf("idp_partial_outage", "IDP_PARTIAL_OUTAGE ", " IDP_PARTIAL_OUTAGE", "", "IDP_PARTIAL_OUTAGE,X").forEach {
            assertEquals(
                UnlockBreakGlassOutcome.IdpAvailable,
                useCase(true).handle("U", tenant, listOf(operator, approver), it),
                it,
            )
        }
        nothingWritten()
    }

    @Test
    fun `the override reason opens the unlock and is recorded in the audit detail and the alert`() {
        val outcome = useCase(true).handle("U", tenant, listOf(operator, approver), "IDP_PARTIAL_OUTAGE")
        assertInstanceOf(UnlockBreakGlassOutcome.Unlocked::class.java, outcome)
        assertEquals(listOf("IDP_PARTIAL_OUTAGE"), audits.map { it.reason })
        assertEquals(listOf(listOf("ACC-002", "UNLOCK", "BG-1", "IDP_PARTIAL_OUTAGE")), alerts)
    }

    @Test
    fun `unavailable provider unlocks with or without a reason and records no reason`() {
        listOf(null, "IDP_PARTIAL_OUTAGE", "idp_partial_outage").forEach {
            inserted.clear()
            alerts.clear()
            audits.clear()
            val outcome = useCase(false).handle("U", tenant, listOf(operator, approver), it)
            assertInstanceOf(UnlockBreakGlassOutcome.Unlocked::class.java, outcome)
            assertEquals(listOf(listOf("ACC-002", "UNLOCK", "BG-1", null)), alerts)
            assertEquals(listOf(null), audits.map { a -> a.reason })
        }
    }

    @Test
    fun `a pair of one person still gets the pair-of-one outcome with the provider available and no override`() {
        assertEquals(UnlockBreakGlassOutcome.PairOfOne, useCase(true).handle("U", tenant, listOf(operator, operator)))
        assertEquals(
            UnlockBreakGlassOutcome.PairNotHeld,
            useCase(true).handle("U", tenant, listOf(operator, IdentityRef("X"))),
        )
        nothingWritten()
    }
}
