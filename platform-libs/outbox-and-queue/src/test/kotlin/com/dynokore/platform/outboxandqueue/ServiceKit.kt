package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * A fixture service (`submission-svc`, `payload-svc`, `reconciliation-svc`) playing its role
 * in a test: its own schema, its own writer, relay and boundary, over the harness's database.
 * `payload-svc`, `submission-svc` and `reconciliation-svc` do not exist yet; these stand in for them.
 */
class ServiceKit(
    harness: OutboxHarness,
    val schema: String,
    val service: String,
    catalogue: EventCatalogue,
) {
    val tenants = TenantContextImpl()
    val boundary = KillableTransactionRunner(harness.fixture.appDataSource(), tenants)
    val writer = JdbcOutboxWriter(boundary, schema, service, "$service-1", catalogue, CLOCK)
    val relay = JdbcOutboxRelay(
        harness.fixture.workerDataSource(),
        TenantScope(tenants, boundary, boundary),
        schema,
        PostgresEventBinding(harness.eventLogForRelay),
        CLOCK,
        batchSize = 50,
    )

    /** Runs [block] in a transaction of [tenant], as an inbound adapter would after binding it (ARC-21). */
    fun <T> inTx(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant)) { boundary.inTransaction(block) }

    /** Runs [block] in a transaction under the reserved platform tenant (INV-Q-2927). */
    fun <T> inPlatformTx(block: () -> T): T = tenants.bindPlatform { boundary.inTransaction(block) }

    /** A context for a document-scoped event (keyed by the Doc UUID). */
    fun docContext(
        tenant: UUID,
        doc: UUID,
        cause: String = "cmd-1",
    ) = EventContext(
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = DocUuid(doc),
        transactionDate = LocalDate.of(2026, 9, 30),
        aggregateKey = doc.toString(),
        aggregateVersion = null,
        correlation = CorrelationId("corr-$doc"),
        causation = CausationId(cause),
    )

    companion object {
        /** The tenant of tests that do not care which one. */
        val TENANT: UUID = UUID.randomUUID()
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
