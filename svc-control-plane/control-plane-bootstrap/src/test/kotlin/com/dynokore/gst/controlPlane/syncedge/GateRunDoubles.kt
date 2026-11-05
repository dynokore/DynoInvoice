package com.dynokore.gst.controlPlane.syncedge

import com.dynokore.gst.controlPlane.application.release.GateRunRepository
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.ReleasePermission
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.domain.release.GateRunKey
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
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

/**
 * Test doubles for the recordGateRun refusal test (INV-T-8749): memory repositories, a permission stub that allows
 * exactly `release-train.certify`, a platform operator token, and a runner that applies the operation's own steps
 * without a transaction. Like TestIdentity it sits outside the packages the application scans, so only a test that
 * lists it puts it in a context.
 */
@Configuration(proxyBeanMethods = false)
class GateRunDoubles {
    val runs = LinkedHashMap<GateRunKey, GateRunRecord>()
    val trains = LinkedHashMap<TrainVersion, ReleaseTrain>()

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

    /** A platform operator token: no tenant. */
    @Bean
    @Primary
    fun doubleResolver() =
        EndUserPrincipalResolver { authorization ->
            if (authorization == "Bearer $USER_TOKEN") EndUserPrincipal(ACTOR, null, emptySet()) else null
        }

    @Bean
    @Primary
    fun doublePermissions() = OperatorPermissions { _, key -> key == ReleasePermission.CERTIFY }

    @Bean
    @Primary
    fun doubleRuns() =
        object : GateRunRepository {
            override fun find(key: GateRunKey) = runs[key]

            override fun insert(record: GateRunRecord) = runs.putIfAbsent(record.key, record) == null

            override fun forTrain(version: TrainVersion) = runs.values.filter { it.trainVersion == version }
        }

    @Bean
    @Primary
    fun doubleTrains() =
        object : TrainRepository {
            override fun find(version: TrainVersion) = trains[version]

            override fun save(train: ReleaseTrain) {
                trains[train.version] = train
            }
        }

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

    companion object {
        const val USER_TOKEN = "user-token"
        const val ACTOR = "rita"
    }
}
