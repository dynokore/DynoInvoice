package com.dynokore.platform.serviceruntime.composition

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The service's DataSources (INV-REQ-1230 b1 and b2, INV-ADR-3968; owner ruling INV-Q-3955): one application
 * DataSource, the runtime login (Spring Boot's `dataSource` from `spring.datasource.*`), which every implementation
 * uses, and an optional [EVENT_LOG] pool for the HA event-log instance. There are no role DataSources: the work
 * that reaches across tenants goes through `<svc>_sys`-owned views and definer functions granted to the runtime
 * login only.
 */
object ServiceDataSources {
    /**
     * The event log on the HA event-log instance (owner ruling INV-Q-3953, coordinator ruling INV-Q-4268), on the
     * runtime login: the outbox-and-queue `EventBinding` publishes, and the event-log reader claims and reads, through
     * it; the event-log component's purge calls `eventlog.purge_batch()` through it. Created from
     * `gst.db.event-log-url` only when it names another instance than `spring.datasource.url`; otherwise (absent or
     * the same URL) it collapses into the application DataSource. Maximum [EVENT_LOG_MAXIMUM] connections and
     * [EventLogPoolProperties.minimumIdle] idle (INV-T-3977 capacity model, coordinator ruling INV-Q-4348).
     */
    const val EVENT_LOG = "eventLogDataSource"

    /** The event-log pool's maximum size (INV-T-3977). */
    const val EVENT_LOG_MAXIMUM = 3
}

/**
 * `service-runtime.event-log-pool.*`: the event-log pool's settings (coordinator ruling INV-Q-4348).
 *
 * @property minimumIdle idle connections kept, default 1; refused above [ServiceDataSources.EVENT_LOG_MAXIMUM].
 */
@ConfigurationProperties(prefix = "service-runtime.event-log-pool")
data class EventLogPoolProperties(
    val minimumIdle: Int = 1,
) {
    init {
        require(minimumIdle in 0..ServiceDataSources.EVENT_LOG_MAXIMUM) {
            "service-runtime.event-log-pool.minimum-idle must be 0..${ServiceDataSources.EVENT_LOG_MAXIMUM}"
        }
    }
}

/**
 * `service-runtime.jobs.*`: the job runtime's settings.
 *
 * @property retention how long finished job runs and ended pauses are kept (T30 slot answer retention, default 30
 *   days). The migration runner writes it to the job-retention row and the job runtime refuses to start when it
 *   differs from the row (coordinator ruling INV-Q-4346 (A)), so both read the same configuration.
 */
@ConfigurationProperties(prefix = "service-runtime.jobs")
data class JobProperties(
    val retention: java.time.Duration = java.time.Duration.ofDays(30),
)

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
    fun requireSchema(): String =
        checkNotNull(
            schema?.takeIf {
                it.isNotBlank()
            },
        ) { "service-runtime.modules.schema must name the service's schema" }
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
