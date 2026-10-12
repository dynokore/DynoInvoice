package com.dynokore.platform.serviceruntime.composition

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The bean names under which a service declares its role DataSources (ARC-20, ARC 4.3 step 1a), the inputs only
 * the service has (INV-T-2875, INV-Q-3710). An implementation that needs one is created only when the service
 * declares it (@ConditionalOnBean by name); see the module README's port table.
 *
 * The application role's DataSource is the service's ordinary `DataSource` bean (Spring Boot's `dataSource`, or
 * the `@Primary` one when the service declares several).
 */
object ServiceDataSources {
    /**
     * The connection the outbox-and-queue implementations use outside any tenant transaction: relay and work-queue
     * claims, offsets, dead letters, retention purges. From outbox-and-queue's (runtime, sys) DDL (INV-T-3978,
     * INV-Q-3953) these reach across tenants only through `<svc>_sys`-owned views and definer functions, granted to the
     * runtime login alone, so this bean must connect as the service's runtime login for them; the job runs and the
     * idempotency purge still use the worker grants until their (runtime, sys) DDL, and this bean goes with the role
     * DataSources in the service-runtime single-DataSource task.
     */
    const val WORKER = "workerDataSource"

    /** The relay role on the event log (INSERT only): the outbox-and-queue `EventBinding` publishes through it. */
    const val EVENT_LOG_RELAY = "eventLogRelayDataSource"

    /** A consumer's event-log claim role: finds records after an offset across tenants. */
    const val EVENT_LOG_CLAIM = "eventLogClaimDataSource"

    /** A consumer's event-log reader role: envelopes of the bound tenant only. */
    const val EVENT_LOG_READER = "eventLogReaderDataSource"

    /** The event-log component's purge role (INV-MOD-2748). */
    const val EVENT_LOG_PURGE = "eventLogPurgeDataSource"
}

/**
 * `service-runtime.modules.*`: the service-only names the L1 implementations need.
 *
 * @property schema the service's own schema, holding its outbox, inbox, queue, audit, configuration and evidence
 *   tables (the `OutboxSchema`, `AuditSchema`, `ConfigSchema` and `EvidenceLedgerSchema` DDL it migrates).
 * @property evidence the evidence-store inputs.
 */
@ConfigurationProperties(prefix = "service-runtime.modules")
data class ModuleProperties(
    val schema: String? = null,
    val evidence: EvidenceProperties = EvidenceProperties(),
) {
    /** [schema], required once an implementation that needs it is created. */
    fun requireSchema(): String = checkNotNull(schema?.takeIf { it.isNotBlank() }) { "service-runtime.modules.schema must name the service's schema" }
}

/**
 * @property ledgerTable the evidence ledger's table in [ModuleProperties.schema].
 * @property legalHoldTable the legal-hold register's table in [ModuleProperties.schema].
 * @property bucket the object-store bucket of the `ObjectStore` binding.
 */
data class EvidenceProperties(
    val ledgerTable: String? = null,
    val legalHoldTable: String? = null,
    val bucket: String? = null,
)

/** Property names the wiring conditions name (one place, so the README and the conditions agree). */
internal object Props {
    const val SCHEMA = "service-runtime.modules.schema"
    const val SERVICE = "gst.service"
    const val LEDGER_TABLE = "service-runtime.modules.evidence.ledger-table"
    const val LEGAL_HOLD_TABLE = "service-runtime.modules.evidence.legal-hold-table"
    const val BUCKET = "service-runtime.modules.evidence.bucket"
    const val EVENT_BINDING = "gst.bindings.event-binding"
    const val OBJECT_STORE = "gst.bindings.object-store"
    const val SECRET_STORE = "gst.bindings.secret-store.implementation"
}
