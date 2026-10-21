package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptEgress.Companion.ATTEMPT_EGRESS
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.application.AttemptLog
import com.dynokore.gst.transport.application.SetTenantCeiling
import com.dynokore.gst.transport.application.SubmitPayload
import com.dynokore.gst.transport.application.SwitchEntityAdapter
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.ApplicationContext
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID

/** The service boundary over a real database: the full TPL-18 context, each test with adapters of its own ids. */
class Harness(
    val context: ApplicationContext,
    val primary: AdapterId,
    val secondary: AdapterId,
) {
    private val actor = "svc:transport-svc"
    val scope: TenantScope = context.getBean(TenantScope::class.java)
    val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
    val dsl: DSLContext = context.getBean(DSLContext::class.java)
    val submit: SubmitPayload = context.getBean(SubmitPayload::class.java)
    val switch: SwitchEntityAdapter = context.getBean(SwitchEntityAdapter::class.java)
    val ceiling: SetTenantCeiling = context.getBean(SetTenantCeiling::class.java)
    val attempts: AttemptLog = context.getBean(AttemptLog::class.java)

    fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = scope.bind(TenantId(tenant), actor) { runner.inTransaction { block() } }

    /** The AP connection record of the adapter, as set up with the rate agreed with the AP. */
    fun connect(
        tenant: UUID,
        adapter: AdapterId,
        agreedRate: String,
    ) = asTenant(tenant) {
        dsl
            .insertInto(AP_CONNECTION)
            .set(AP_CONNECTION.TENANT_ID, tenant)
            .set(AP_CONNECTION.ADAPTER_ID, adapter.value)
            .set(AP_CONNECTION.AGREED_RATE, BigDecimal(agreedRate))
            .execute()
    }

    fun submitPayload(
        tenant: UUID,
        entity: String,
        doc: DocRef,
        payload: String = "<Invoice/>",
    ): TransportOutcome = scope.bind(TenantId(tenant), actor) { submit.submit(EntityRef(entity), doc, payload) }

    fun assign(
        tenant: UUID,
        entity: String,
        adapter: AdapterId,
    ) = scope.bind(TenantId(tenant), actor) { switch.switch(EntityRef(entity), adapter) }

    /** Attempt ids with the adapter and the egress count of each, for the tenant. */
    fun attemptLog(tenant: UUID): List<Triple<UUID, String, Int>> =
        asTenant(tenant) {
            dsl
                .select(SUBMISSION_ATTEMPT.ID, SUBMISSION_ATTEMPT.ADAPTER_ID)
                .from(SUBMISSION_ATTEMPT)
                .fetch()
                .map { row ->
                    val attempt = checkNotNull(row.value1())
                    val egress = dsl.fetchCount(ATTEMPT_EGRESS, ATTEMPT_EGRESS.ATTEMPT_ID.eq(attempt))
                    Triple(attempt, checkNotNull(row.value2()), egress)
                }
        }

    /** Every `table.column` of the generated schema. */
    fun schemaColumns(): List<String> =
        com.dynokore.gst.transport.adapters.persistence.jooq.DefaultCatalog.DEFAULT_CATALOG.TRANSPORT.tables
            .flatMap { table -> table.fields().map { "${table.name}.${it.name}".lowercase() } }

    companion object {
        fun run(
            dir: Path,
            vararg overrides: String,
            block: (Harness) -> Unit,
        ) {
            val primary = AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
            val secondary = AdapterId("s-${UUID.randomUUID().toString().take(UNIQUE)}")
            val adapters =
                arrayOf(
                    "transport.ap.adapters[0].id=${primary.value}",
                    "transport.ap.adapters[0].path=primary",
                    "transport.ap.adapters[1].id=${secondary.value}",
                    "transport.ap.adapters[1].path=secondary",
                )
            TransportContext.online(dir, *adapters, *overrides).run { context ->
                check(context.startupFailure == null) { "context failed to start: ${context.startupFailure}" }
                block(Harness(context, primary, secondary))
            }
        }

        private const val UNIQUE = 12
    }
}
