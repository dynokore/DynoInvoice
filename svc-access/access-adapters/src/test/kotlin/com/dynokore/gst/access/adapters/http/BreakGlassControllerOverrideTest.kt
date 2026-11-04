package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.ApproveBreakGlassUnlockUseCase
import com.dynokore.gst.access.application.breakglass.BreakGlassAccounts
import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassApproval
import com.dynokore.gst.access.application.breakglass.BreakGlassApprovals
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.BreakGlassSetting
import com.dynokore.gst.access.application.breakglass.BreakGlassSettings
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.ResealBreakGlassUseCase
import com.dynokore.gst.access.application.breakglass.SealedAccountKeeper
import com.dynokore.gst.access.application.breakglass.SignInAttempts
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.application.idp.IdentityProviderProbe
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.gst.access.domain.breakglass.BreakGlassPairing
import com.dynokore.gst.access.domain.breakglass.SignInBackoff
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
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** `unlockBreakGlass` while the identity provider counts as available (INV-Q-11557 A, INV-AC-9185). */
class BreakGlassControllerOverrideTest {
    private val tenantId = TenantId(UUID.randomUUID())
    private val tenant = TenantRef(tenantId.toString())
    private val operator = IdentityRef("IDN-OP")
    private val approver = IdentityRef("IDN-AP")
    private val inserted = mutableListOf<Int>()
    private val alerts = mutableListOf<String?>()
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
        mapOf(operator to grantOf(operator, Role.TENANT_ADMIN), approver to grantOf(approver, Role.TAX_APPROVER))

    private val grantRepository: GrantRepository =
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

    private val tenants =
        object : TenantContext {
            override fun current() = tenantId

            override fun currentActor() = "user:test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val clock = Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"), ZoneOffset.UTC)
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    private val sessions =
        object : BreakGlassSessions {
            override fun insert(
                sessionHours: Int,
                unlockedAt: Instant,
                approvedBy: List<String>,
            ): BreakGlassSession {
                inserted += sessionHours
                return BreakGlassSession(
                    "BG-1",
                    tenant.value,
                    sessionHours,
                    unlockedAt,
                    unlockedAt.plusSeconds(60),
                    approvedBy,
                    false,
                )
            }

            override fun find(ref: String): BreakGlassSession? = null

            override fun markSealed(ref: String) = Unit

            override fun insertAction(
                unlockRef: String,
                action: BreakGlassAction,
                target: String?,
                callerService: String,
            ): String = error("not used")
        }
    private val effects =
        BreakGlassEffects(
            object : BreakGlassAlerts {
                override fun raise(
                    code: String,
                    action: String,
                    unlockRef: String,
                ) = error("the unlock must use the form with the override reason")

                override fun raise(
                    code: String,
                    action: String,
                    unlockRef: String,
                    overrideReason: String?,
                ) {
                    alerts += overrideReason
                }
            },
            object : GrantAudit {
                override fun record(entry: GrantAuditEntry) {
                    audits += entry
                }
            },
        )

    /** These tests are about the override: every named approver has an approval. */
    private val approvals =
        object : BreakGlassApprovals {
            override fun replace(
                approver: String,
                idempotencyKey: java.util.UUID,
                madeAt: Instant,
                window: Duration,
            ): com.dynokore.gst.access.application.breakglass.StoredApproval = error("not used")

            override fun consume(
                approvers: Collection<String>,
                after: Instant,
                unlockRef: String,
                consumedAt: Instant,
            ): Int = approvers.size
        }

    private val setting = BreakGlassSettings { BreakGlassSetting(4, BreakGlassPairing.DEFAULT) }

    private val keeper =
        SealedAccountKeeper(
            object : BreakGlassAccounts {
                override fun insertIfAbsent(verifier: String) = false

                override fun verifier(): String? = null

                override fun replaceVerifier(verifier: String) = false
            },
            { error("the unlock tests create no credential") },
        )
    private val effectsAudit =
        object : GrantAudit {
            override fun record(entry: GrantAuditEntry) {
                audits += entry
            }
        }

    private val sealing =
        BreakGlassSealing(
            SignInWithBackoffUseCase(
                tx,
                sessions,
                keeper,
                object : SignInAttempts {
                    override fun readForUpdate(unlockRef: String) = SignInBackoff.INITIAL

                    override fun save(
                        unlockRef: String,
                        state: SignInBackoff,
                    ) = Unit
                },
                effects,
                clock,
            ),
            ResealBreakGlassUseCase(tx, sessions, keeper, effectsAudit, clock),
            { false },
        )

    private fun controller(idpUp: Boolean): BreakGlassController {
        val availability = IdentityProviderAvailability("http://idp", IdentityProviderProbe { idpUp }, 1)
        availability.probeOnce()
        val unlock =
            UnlockBreakGlassUseCase(
                tx,
                sessions,
                grantRepository,
                setting,
                effects,
                clock,
                availability,
                approvals,
                Duration.ofMinutes(15),
            )
        return BreakGlassController(
            unlock,
            ApproveBreakGlassUnlockUseCase(
                tx,
                approvals,
                grantRepository,
                setting,
                PermissionMatrix.EMPTY,
                object : GrantAudit {
                    override fun record(entry: GrantAuditEntry) = error("not used")
                },
                clock,
                Duration.ofMinutes(15),
            ),
            RecordBreakGlassActionUseCase(
                tx,
                sessions,
                effects,
                clock,
            ),
            tenants,
            object : ServiceCaller {
                override fun current(): String? = "svc"
            },
            sealing,
        )
    }

    private fun request(
        reason: UnlockBreakGlassRequest.OverrideReason?,
        vararg people: String = arrayOf("IDN-OP", "IDN-AP"),
    ) = UnlockBreakGlassRequest(tenant.value, people.toList(), reason)

    @Test
    fun `an unlock with the provider available and no reason is 409 with a detail and no code and opens nothing`() {
        val refusal =
            org.junit.jupiter.api.Assertions.assertThrows(ApiConflict::class.java) {
                controller(true).unlockBreakGlass(UUID.randomUUID(), request(null))
            }
        val response = ApiRefusalAdvice().conflict(refusal)
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = JsonMapper.builder().build().readTree(response.body)
        assertTrue(body.get("detail").asString().contains("IDP_PARTIAL_OUTAGE"))
        assertFalse(body.has("code"))
        assertTrue(inserted.isEmpty() && audits.isEmpty())
    }

    @Test
    fun `the override reason answers 201 and the audit detail equals it`() {
        val response =
            controller(true).unlockBreakGlass(
                UUID.randomUUID(),
                request(UnlockBreakGlassRequest.OverrideReason.IDP_PARTIAL_OUTAGE),
            )
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(listOf("IDP_PARTIAL_OUTAGE"), audits.map { it.reason })
        assertEquals(listOf<String?>("IDP_PARTIAL_OUTAGE"), alerts)
    }

    @Test
    fun `a pair of one person is still 400 with the provider available and no reason`() {
        val refusal =
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
                controller(true).unlockBreakGlass(UUID.randomUUID(), request(null, "IDN-OP", "IDN-OP"))
            }
        assertFalse(refusal is ApiConflict)
    }
}
