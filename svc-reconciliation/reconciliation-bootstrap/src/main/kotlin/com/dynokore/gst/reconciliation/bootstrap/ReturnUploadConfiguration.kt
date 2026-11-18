package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.events.OutboxGstReturnRecordedPublisher
import com.dynokore.gst.reconciliation.adapters.persistence.JooqReturnRecords
import com.dynokore.gst.reconciliation.adapters.persistence.QueueRunTrigger
import com.dynokore.gst.reconciliation.application.returns.GstReturnRecordedPublisher
import com.dynokore.gst.reconciliation.application.returns.ReturnRecords
import com.dynokore.gst.reconciliation.application.returns.RunTrigger
import com.dynokore.gst.reconciliation.application.returns.UploadGstReturn
import com.dynokore.gst.reconciliation.application.run.ReturnFiguresSource
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.WorkQueue
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The beans of the return upload (INV-T-24367, INV-REQ-653): the use case and the adapters of its three ports, and the
 * store as the return source of the run assembly. The use case opens no transaction; the controller does.
 */
@Configuration(proxyBeanMethods = false)
class ReturnUploadConfiguration {
    /** One store bean: the [ReturnRecords] of the upload and the [ReturnFiguresSource] of the run assembly. */
    @Bean
    fun jooqReturnRecords(dsl: DSLContext): JooqReturnRecords = JooqReturnRecords(dsl)

    @Bean
    fun gstReturnRecordedPublisher(outbox: OutboxWriter): GstReturnRecordedPublisher =
        OutboxGstReturnRecordedPublisher(outbox)

    @Bean
    fun runTrigger(queue: WorkQueue): RunTrigger = QueueRunTrigger(queue)

    @Bean
    fun uploadGstReturn(
        records: ReturnRecords,
        publisher: GstReturnRecordedPublisher,
        trigger: RunTrigger,
        clock: ObjectProvider<Clock>,
    ): UploadGstReturn = UploadGstReturn(records, publisher, trigger, clock.getIfAvailable { Clock.systemUTC() })
}
