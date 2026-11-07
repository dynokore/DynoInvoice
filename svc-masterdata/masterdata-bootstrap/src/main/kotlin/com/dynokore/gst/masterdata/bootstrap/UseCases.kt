package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqCounterpartyRepository
import com.dynokore.gst.masterdata.adapters.persistence.JooqPendingChangeRepository
import com.dynokore.gst.masterdata.adapters.persistence.JooqStructureNodeRepository
import com.dynokore.gst.masterdata.adapters.persistence.JooqTripleRepository
import com.dynokore.gst.masterdata.application.counterparty.ApplyStructureNode
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.counterparty.ReadCounterpartyHistory
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.counterparty.ResolveCounterparty
import com.dynokore.gst.masterdata.application.counterparty.StructureNodeRepository
import com.dynokore.gst.masterdata.application.identity.BackdatedCorrections
import com.dynokore.gst.masterdata.application.identity.ConfirmIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ConfirmerAuthority
import com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy
import com.dynokore.gst.masterdata.application.identity.ConfirmerRoles
import com.dynokore.gst.masterdata.application.identity.PendingChangeRepository
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.application.identity.TripleRepository
import com.dynokore.gst.masterdata.adapters.http.AuditLogExportAudit
import com.dynokore.gst.masterdata.adapters.http.ProblemsFailureEnvelope
import com.dynokore.gst.masterdata.adapters.persistence.JooqGapReportStore
import com.dynokore.gst.masterdata.application.gapreport.ExportAudit
import com.dynokore.gst.masterdata.application.gapreport.ExportGapReport
import com.dynokore.gst.masterdata.application.gapreport.FailureEnvelope
import com.dynokore.gst.masterdata.application.gapreport.GapReportStore
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.ReadGapReport
import com.dynokore.gst.masterdata.application.gapreport.RunGapReport
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * TPL-18: the only place a use case is constructed. No DataSource bean here or anywhere in the service (owner ruling
 * INV-Q-4196): the one application pool is Spring Boot's `dataSource` from spring.datasource.* (TPL-16).
 */
@Configuration(proxyBeanMethods = false)
class UseCases {
    @Bean
    fun tripleRepository(
        dsl: DSLContext,
        clock: Clock,
    ): TripleRepository = JooqTripleRepository(dsl, clock)

    @Bean
    fun resolveIdentityTriple(
        tx: TransactionRunner,
        triples: TripleRepository,
    ) = ResolveIdentityTriple(tx, triples)

    @Bean
    fun pendingChangeRepository(
        dsl: DSLContext,
        clock: Clock,
    ): PendingChangeRepository = JooqPendingChangeRepository(dsl, clock)

    @Bean
    fun proposeIdentityTripleChange(
        tx: TransactionRunner,
        changes: PendingChangeRepository,
    ) = ProposeIdentityTripleChange(tx, changes)

    @Bean
    fun confirmIdentityTripleChange(
        tx: TransactionRunner,
        changes: PendingChangeRepository,
        triples: TripleRepository,
        authority: ConfirmerAuthority,
        corrections: BackdatedCorrections,
    ) = ConfirmIdentityTripleChange(tx, changes, triples, authority, corrections)

    @Bean
    fun counterpartyRepository(
        dsl: DSLContext,
        clock: Clock,
    ): CounterpartyRepository = JooqCounterpartyRepository(dsl, clock)

    @Bean
    fun structureNodeRepository(
        dsl: DSLContext,
        clock: Clock,
    ): StructureNodeRepository = JooqStructureNodeRepository(dsl, clock)

    @Bean
    fun resolveCounterparty(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        structure: StructureNodeRepository,
    ) = ResolveCounterparty(tx, parties, structure)

    @Bean
    fun recordCounterparty(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        clock: Clock,
        publisher: MasterDataResolvedPublisher,
    ) = RecordCounterparty(tx, parties, clock, publisher)

    @Bean
    fun readCounterpartyHistory(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
    ) = ReadCounterpartyHistory(tx, parties)

    @Bean
    fun applyStructureNode(
        tx: TransactionRunner,
        nodes: StructureNodeRepository,
    ) = ApplyStructureNode(tx, nodes)

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
    @Bean(destroyMethod = "shutdown")
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
    fun failureEnvelope(): FailureEnvelope = ProblemsFailureEnvelope()

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
        failure: FailureEnvelope,
        clock: Clock,
    ) = RunGapReport(tx, parties, store, register, runner, failure, clock)

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
