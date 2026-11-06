package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.events.AccreditationAlertsOutbox
import com.dynokore.gst.controlPlane.adapters.http.TaskCursor
import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationTaskRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.ReleaseAccreditationLinksJooq
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlerts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationRecords
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepPorts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskQueries
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskRepository
import com.dynokore.gst.controlPlane.application.accreditation.ConcludeAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseAccreditationLinks
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The re-accreditation tasks and the alert of the release pipeline (INV-REQ-1365, INV-T-7323). */
@Configuration(proxyBeanMethods = false)
class AccreditationTaskConfiguration {
    @Bean
    fun accreditationTaskRepository(dsl: DSLContext): AccreditationTaskRepository = AccreditationTaskRepositoryJooq(dsl)

    @Bean
    fun releaseAccreditationLinks(dsl: DSLContext): ReleaseAccreditationLinks = ReleaseAccreditationLinksJooq(dsl)

    @Bean
    fun accreditationAlerts(outbox: OutboxWriter): AccreditationAlerts = AccreditationAlertsOutbox(outbox)

    @Bean
    fun accreditationStepPorts(
        tasks: AccreditationTaskRepository,
        links: ReleaseAccreditationLinks,
        alerts: AccreditationAlerts,
        records: AccreditationRecords,
    ) = AccreditationStepPorts(tasks, links, alerts, records)

    /** The accreditation step of the release pipeline: its own transaction; a failure is logged, never thrown. */
    @Bean
    fun openAccreditationTask(
        ports: AccreditationStepPorts,
        transactions: TransactionRunner,
        clock: Clock,
    ) = OpenAccreditationTask(ports, transactions, clock, { target, failure ->
        LoggerFactory
            .getLogger(OpenAccreditationTask::class.java)
            .warn("the accreditation task step of {} {} failed; the release stands", target.kind, target.ref, failure)
    })

    @Bean
    fun accreditationTaskQueries(
        tasks: AccreditationTaskRepository,
        permissions: OperatorPermissions,
        transactions: TransactionRunner,
    ) = AccreditationTaskQueries(tasks, permissions, transactions)

    @Bean
    fun concludeAccreditationTask(
        tasks: AccreditationTaskRepository,
        permissions: OperatorPermissions,
        clock: Clock,
    ) = ConcludeAccreditationTask(tasks, permissions, clock)

    /**
     * The cursor key is read through the SecretStore at every list call and never cached (INV-Q-15432 A, STK-29), so
     * a cursor survives a restart and is the same on every replica; the deployment provisions the secret.
     */
    @Bean
    fun taskCursor(secrets: SecretStore) =
        TaskCursor { secrets.read(SecretRef(CURSOR_KEY_REF)).reveal().toByteArray(Charsets.UTF_8) }

    companion object {
        /** The SecretStore ref of the HMAC-SHA256 key of the listAccreditationTasks cursor. */
        const val CURSOR_KEY_REF = "controlplane/accreditation-task-cursor-key"
    }
}
