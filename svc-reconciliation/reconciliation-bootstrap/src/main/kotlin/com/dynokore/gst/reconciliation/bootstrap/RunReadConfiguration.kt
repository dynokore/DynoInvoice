package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.persistence.JooqCompletedRuns
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReader
import com.dynokore.gst.reconciliation.application.run.CompletedRuns
import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RunReader
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The beans of the run store and its two reads (INV-T-24376, INV-REQ-656): the append-only store and the reader as
 * adapters of their ports, and the `listRuns` and `getRun` use cases. The use cases open no transaction; the controller
 * does.
 */
@Configuration(proxyBeanMethods = false)
class RunReadConfiguration {
    @Bean
    fun completedRuns(dsl: DSLContext): CompletedRuns = JooqCompletedRuns(dsl)

    @Bean
    fun runReader(dsl: DSLContext): RunReader = JooqRunReader(dsl)

    @Bean
    fun listRuns(reader: RunReader): ListRuns = ListRuns(reader)

    @Bean
    fun getRun(reader: RunReader): GetRun = GetRun(reader)
}
