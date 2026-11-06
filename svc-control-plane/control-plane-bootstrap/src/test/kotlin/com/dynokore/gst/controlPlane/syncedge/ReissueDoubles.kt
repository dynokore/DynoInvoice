package com.dynokore.gst.controlPlane.syncedge

import com.dynokore.gst.controlPlane.application.commercial.FirstAdministratorAccess
import com.dynokore.gst.controlPlane.application.commercial.FirstAdministratorAnswer
import com.dynokore.gst.controlPlane.application.commercial.InvitationCode
import com.dynokore.gst.controlPlane.application.commercial.InvitationDelivery
import com.dynokore.gst.controlPlane.application.commercial.ProvisionFirstAdministratorCall
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRecord
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRepository
import com.dynokore.gst.controlPlane.application.commercial.ReissueAttempt
import com.dynokore.gst.controlPlane.application.commercial.ReissueAttemptRepository
import com.dynokore.gst.controlPlane.application.commercial.ReissueFirstAdministratorCall
import com.dynokore.gst.controlPlane.application.commercial.ReissueFirstAdministratorInvitation
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Test doubles for the reissue HTTP test (INV-T-8228): a memory provisioning request, a scripted access-svc, a
 * permission stub that admits exactly `tenant-invitation.reissue` for the actors in [permitted] (a role name decides
 * nothing), and a runner that applies the operation's own steps without a transaction.
 */
@Configuration(proxyBeanMethods = false)
class ReissueDoubles {
    val contract = CommercialRef("CTR-2026-0091")
    var status = ProvisioningStatus.CREATED
    var answer: FirstAdministratorAnswer =
        FirstAdministratorAnswer.Issued("INVN-NEW", InvitationCode("code-secret-new"))
    val permitted = LinkedHashSet<String>()
    val accessCalls = ArrayList<ReissueFirstAdministratorCall>()
    val messages = ArrayList<String>()
    private val attempts = HashMap<UUID, ReissueAttempt>()

    @Bean
    @Primary
    fun doubleWorkloadVerifier() =
        WorkloadTokenVerifier { token ->
            val value = token?.value
            if (value != null && value.startsWith("token-")) {
                WorkloadVerification.Verified("sa-" + value.removePrefix("token-"))
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }

    @Bean
    @Primary
    fun doubleResolver() =
        EndUserPrincipalResolver { authorization ->
            if (authorization != null && authorization.startsWith("Bearer user-")) {
                EndUserPrincipal(authorization.removePrefix("Bearer user-"), null, emptySet())
            } else {
                null
            }
        }

    @Bean
    @Primary
    fun doublePermissions() =
        OperatorPermissions { actor, key ->
            actor in permitted && key == ReissueFirstAdministratorInvitation.PERMISSION_KEY
        }

    @Bean
    @Primary
    fun doubleProvisionings() =
        object : ProvisioningRepository {
            override fun findByContract(contractReference: CommercialRef) =
                if (contractReference == contract) {
                    ProvisioningRecord(
                        TenantProvisioning(
                            contract,
                            CommercialRef("TEN-1"),
                            "Acme",
                            PlanRef("P"),
                            "admin@acme.example",
                            RegionKey("SG"),
                            status,
                            "INVN-OLD",
                            Instant.parse("2026-11-01T08:00:00Z"),
                        ),
                        UUID.randomUUID(),
                        "fp",
                    )
                } else {
                    null
                }

            override fun findByKey(
                idempotencyKey: UUID,
                recordedBy: String,
            ) = null

            override fun save(
                record: ProvisioningRecord,
                recordedBy: String,
            ) = error("not used")

            override fun markInvited(
                contractReference: CommercialRef,
                invitationRef: String,
            ) = error("not used")

            override fun markRefused(contractReference: CommercialRef) = error("not used")
        }

    @Bean
    @Primary
    fun doubleAttempts() =
        object : ReissueAttemptRepository {
            override fun find(
                idempotencyKey: UUID,
                recordedBy: String,
            ) = attempts[idempotencyKey]

            override fun save(
                attempt: ReissueAttempt,
                recordedBy: String,
            ) {
                attempts[attempt.idempotencyKey] = attempt
            }

            override fun complete(
                idempotencyKey: UUID,
                recordedBy: String,
                invitationRef: String,
            ) {
                attempts[idempotencyKey] = attempts.getValue(idempotencyKey).copy(invitationRef = invitationRef)
            }
        }

    @Bean
    @Primary
    fun doubleDelivery() =
        InvitationDelivery(
            { c, t, issued, issuedAt, expiresAt ->
                "signed:$c:$t:${issued.invitationRef}:${issued.invitationCode.value}:$issuedAt:$expiresAt"
            },
            { _, email, token, _ -> messages += "$email|$token" },
            object : FirstAdministratorAccess {
                override fun provision(call: ProvisionFirstAdministratorCall) = error("not used")

                override fun reissue(call: ReissueFirstAdministratorCall): FirstAdministratorAnswer {
                    accessCalls += call
                    return answer
                }
            },
            Clock.fixed(Instant.parse("2026-11-20T08:00:00Z"), ZoneOffset.UTC),
            Duration.ofDays(14),
        )

    @Bean
    @Primary
    fun doublePlatformBinding() =
        object : PlatformTenantBinding {
            override fun <T> bindPlatform(
                actor: String,
                block: () -> T,
            ): T = block()
        }

    @Bean
    @Primary
    fun doubleRunner() =
        object : OperationRunner {
            override fun <C, S, R> run(
                operation: DeclaredOperation<C, S, R>,
                actor: String,
                command: C,
            ): OperationOutcome<R> {
                if (!operation.permits(actor, command)) return OperationOutcome.NotPermitted
                val state = operation.load(command)
                val prior = operation.priorResult(command, state)
                val unmet = if (prior == null) operation.unmetPrecondition(command, state) else null
                return when {
                    prior != null -> OperationOutcome.Repeated(prior)
                    unmet != null -> OperationOutcome.PreconditionUnmet(unmet)
                    else -> OperationOutcome.Performed(operation.perform(command, state))
                }
            }
        }
}
