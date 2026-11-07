package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.ImportEvidenceOutbox
import com.dynokore.gst.masterdata.adapters.http.AuditLogExportAudit
import com.dynokore.gst.masterdata.adapters.persistence.JooqDryRunStore
import com.dynokore.gst.masterdata.adapters.persistence.JooqGapReportStore
import com.dynokore.gst.masterdata.adapters.persistence.JooqImportStore
import com.dynokore.gst.masterdata.adapters.persistence.JooqStaleRecordTenantSource
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.gapreport.ExportAudit
import com.dynokore.gst.masterdata.application.gapreport.ExportGapReport
import com.dynokore.gst.masterdata.application.gapreport.GapReportStore
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.ReadGapReport
import com.dynokore.gst.masterdata.application.gapreport.RunGapReport
import com.dynokore.gst.masterdata.application.gapreport.StaleRecordTenantSource
import com.dynokore.gst.masterdata.application.gapreport.SweepStaleRecords
import com.dynokore.gst.masterdata.application.imports.DryRunCounterpartyImport
import com.dynokore.gst.masterdata.application.imports.DryRunStore
import com.dynokore.gst.masterdata.application.imports.ImportCounterparties
import com.dynokore.gst.masterdata.application.imports.ImportEvidence
import com.dynokore.gst.masterdata.application.imports.ImportStore
import com.dynokore.gst.masterdata.application.imports.ReadDryRun
import com.dynokore.gst.masterdata.application.imports.ReadImportRecord
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** TPL-18: the gap report use cases, their store and the bounded executor of the long commands (INV-T-4944). */
@Suppress("TooManyFunctions") // one bean method per wired part, as UseCases does (INV-Q-4629)
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

    @Bean
    fun dryRunStore(
        dsl: DSLContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): DryRunStore = JooqDryRunStore(dsl, clock, mapper)

    @Bean
    fun importStore(
        dsl: DSLContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): ImportStore = JooqImportStore(dsl, clock, mapper)

    @Bean
    fun importEvidence(
        recorder: EvidenceArtefactRecorder,
        tenants: TenantContext,
    ): ImportEvidence = ImportEvidenceOutbox(recorder, tenants)

    @Bean
    fun dryRunCounterpartyImport(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        store: DryRunStore,
        runner: LongRunner,
    ) = DryRunCounterpartyImport(tx, parties, store, runner)

    @Bean
    fun readDryRun(
        tx: TransactionRunner,
        store: DryRunStore,
    ) = ReadDryRun(tx, store)

    @Bean
    @Suppress("LongParameterList")
    fun importCounterparties(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        dryRuns: DryRunStore,
        imports: ImportStore,
        clock: Clock,
        publisher: MasterDataResolvedPublisher,
        evidence: ImportEvidence,
        runner: LongRunner,
    ) = ImportCounterparties(tx, parties, dryRuns, imports, clock, publisher, evidence, runner)

    @Bean
    fun readImportRecord(
        tx: TransactionRunner,
        store: ImportStore,
    ) = ReadImportRecord(tx, store)

    /**
     * The sweep of RUNNING records older than `masterdata.long-command.max-run` (INV-Q-17291). The ISO-8601 duration
     * has no default in code: a start without it fails.
     */
    @Bean
    @Suppress("LongParameterList") // the stores of the three record kinds
    fun sweepStaleRecords(
        tx: TransactionRunner,
        clock: Clock,
        gapReports: GapReportStore,
        dryRuns: DryRunStore,
        imports: ImportStore,
        source: StaleRecordTenantSource,
        tenants: TenantContext,
        principal: WorkloadPrincipal,
        environment: Environment,
    ) = SweepStaleRecords(
        tx,
        clock,
        Duration.parse(environment.getRequiredProperty("masterdata.long-command.max-run")),
        source,
        tenants,
        principal.value,
        listOf(gapReports::failRunningBefore, dryRuns::failRunningBefore, imports::failRunningBefore),
    )

    @Bean
    fun staleRecordTenantSource(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        principal: WorkloadPrincipal,
        transactions: TransactionRunner,
    ): StaleRecordTenantSource = JooqStaleRecordTenantSource(dsl, platform, principal, transactions)

    private companion object {
        const val WORKERS = 4
        const val QUEUE = 16
        const val CONTRACT_X_TIMEOUT_MS = 2000L
    }
}
