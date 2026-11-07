package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.AuditLogExportAudit
import com.dynokore.gst.masterdata.adapters.persistence.JooqGapReportStore
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.gapreport.ExportAudit
import com.dynokore.gst.masterdata.application.gapreport.ExportGapReport
import com.dynokore.gst.masterdata.application.gapreport.GapReportStore
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.ReadGapReport
import com.dynokore.gst.masterdata.application.gapreport.RunGapReport
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** TPL-18: the gap report use cases, their store and the bounded executor of the long commands (INV-T-4944). */
@Configuration(proxyBeanMethods = false)
class GapReportUseCases {
    @Bean
    fun gapReportStore(
        dsl: DSLContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): GapReportStore = JooqGapReportStore(dsl, clock, mapper)

    /**
     * The bounded executor of the long commands (INV-Q-17291) and the wait of the operations, 2,000 ms, the `x-timeout`
     * that the published contract gives `runGapReport`, `dryRunCounterpartyImport` and `importCounterparties`.
     */
    @Bean
    fun longCommandExecutor(): ThreadPoolExecutor =
        ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE)) { r ->
            Thread(r, "long-command").also { it.isDaemon = true }
        }

    @Bean
    fun longRunner(
        tenants: TenantContext,
        executor: ThreadPoolExecutor,
    ) = LongRunner(tenants, executor, Duration.ofMillis(CONTRACT_X_TIMEOUT_MS))

    @Bean
    fun exportAudit(
        writer: AuditWriter,
        tenants: TenantContext,
    ): ExportAudit = AuditLogExportAudit(writer, tenants)

    @Bean
    fun runGapReport(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        store: GapReportStore,
        register: LookupGstRegister,
        runner: LongRunner,
    ) = RunGapReport(tx, parties, store, register, runner)

    @Bean
    fun readGapReport(
        tx: TransactionRunner,
        store: GapReportStore,
    ) = ReadGapReport(tx, store)

    @Bean
    fun exportGapReport(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        store: GapReportStore,
        audit: ExportAudit,
    ) = ExportGapReport(tx, parties, store, audit)

    private companion object {
        const val WORKERS = 4
        const val QUEUE = 16
        const val CONTRACT_X_TIMEOUT_MS = 2000L
    }
}
