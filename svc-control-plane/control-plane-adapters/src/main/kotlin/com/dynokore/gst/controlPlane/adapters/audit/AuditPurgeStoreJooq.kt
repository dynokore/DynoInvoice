package com.dynokore.gst.controlPlane.adapters.audit

import com.dynokore.gst.controlPlane.application.audit.AuditEntryPurger
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeResult
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** What the request adds to the purge's own audit entry: the reason (the Idempotency-Key) and the request ids. */
data class AuditPurgeRequest(
    val reason: String,
    val correlationId: String,
    val causationId: String,
)

/** The request being served; the controller binds it (INV-T-3099). */
fun interface AuditPurgeRequestSource {
    fun current(): AuditPurgeRequest
}

/**
 * The purger port over `controlplane.purge_audit_entries` (INV-T-3984), through the jOOQ DSL. The envelope fields
 * of the purge's own entry are staged here by audit-log's [AuditStager]; the function checks them, sets actor,
 * tenant, action, scope, prior state and time itself, and writes the entry before the delete. It runs in the
 * command's transaction, bound to the platform tenant and the actor, so the function's `bound_actor()` is the caller.
 */
class AuditPurgeStoreJooq(
    private val dsl: DSLContext,
    private val stager: AuditStager,
) : AuditEntryPurger {
    override fun purge(
        actor: String,
        cutOff: Instant,
        reason: String,
        context: RequestContext,
    ): AuditPurgeResult {
        val staged =
            stager.stage(
                AuditEntry(actor, ACTION, SCOPE, reason),
                EventContext(
                    tenant = TenantId.PLATFORM,
                    entity = EntityRef.PLATFORM,
                    docUuid = null,
                    transactionDate = null,
                    aggregateKey = AGGREGATE_KEY,
                    aggregateVersion = null,
                    correlation = CorrelationId(context.correlationId),
                    causation = CausationId(context.causationId),
                ),
            )
        val envelope = staged.envelope
        val deleted =
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, FUNCTION),
                        Long::class.java,
                        DSL.`val`(cutOff.atOffset(ZoneOffset.UTC)),
                        DSL.`val`(reason),
                        DSL.`val`(UUID.fromString(envelope.id.value)),
                        DSL.`val`(envelope.source),
                        DSL.`val`(envelope.subject),
                        DSL.`val`(envelope.instanceId),
                        DSL.`val`(envelope.correlation.value),
                        DSL.`val`(envelope.causation.value),
                    ),
                ).fetchSingle()
                .value1()
        return AuditPurgeResult(Math.toIntExact(deleted), envelope.id.value)
    }

    private companion object {
        const val SCHEMA = "controlplane"
        const val FUNCTION = "purge_audit_entries"
        const val ACTION = "purgeAuditEntries"
        const val SCOPE = "platform (control-plane-svc audit_entry)"
        const val AGGREGATE_KEY = "audit-purge"
    }
}
