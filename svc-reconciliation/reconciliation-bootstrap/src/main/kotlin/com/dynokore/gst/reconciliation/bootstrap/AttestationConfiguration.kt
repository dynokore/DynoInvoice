package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAttestationWriter
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunPreparers
import com.dynokore.gst.reconciliation.adapters.policy.PolicyReaderSingleAttestation
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.workpaper.AttestWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestationWriter
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.RunPreparers
import com.dynokore.gst.reconciliation.application.workpaper.SingleAttestationPolicy
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.RelaxationReader
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The beans of the attestation (INV-T-24437, INV-AC-24436): the preparer read, the attestation writer, the
 * sole-operator policy over the platform `PolicyReader` and `RelaxationReader`, and the `attestWorkPaper` use case.
 * No trigger and no controller is declared here; the use case opens no transaction, the controller does.
 */
@Configuration(proxyBeanMethods = false)
class AttestationConfiguration {
    @Bean
    fun runPreparers(dsl: DSLContext): RunPreparers = JooqRunPreparers(dsl)

    @Bean
    fun runAttestationWriter(dsl: DSLContext): RunAttestationWriter = JooqRunAttestationWriter(dsl)

    @Bean
    fun singleAttestationPolicy(
        policy: PolicyReader,
        relaxations: RelaxationReader,
    ): SingleAttestationPolicy = PolicyReaderSingleAttestation(policy, relaxations)

    @Bean
    @Suppress("LongParameterList")
    fun attestWorkPaper(
        runs: RunReader,
        workPaper: GetWorkPaper,
        preparers: RunPreparers,
        policy: SingleAttestationPolicy,
        attestations: RunAttestations,
        writer: RunAttestationWriter,
        clock: ObjectProvider<Clock>,
    ): AttestWorkPaper =
        AttestWorkPaper(
            runs,
            workPaper,
            preparers,
            policy,
            attestations,
            writer,
            clock.getIfAvailable { Clock.systemUTC() },
        )
}
