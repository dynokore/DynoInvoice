package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.StagedAuditEntry
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.identity.CurrentOperatorSession
import com.dynokore.platform.serviceruntime.identity.OperatorSession
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Prefixes the case to every audit entry recorded under an operator session (INV-REQ-862 b4; coordinator ruling
 * INV-Q-4521): reason `case <case>` when the entry has none, `case <case>: <own reason>` when it has one, so an
 * override's own reason is kept in full (INV-REQ-861 b2, INV-REQ-1180). Added once, before [delegate] records the
 * entry in the caller's transaction. Outside a session the entry is recorded unchanged.
 */
open class OperatorSessionAuditWriter(
    val delegate: AuditWriter,
) : AuditWriter {
    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope = delegate.record(underSession(entry), context)

    /** [entry] with the current session's case prefixed to its reason; unchanged outside a session. */
    protected fun underSession(entry: AuditEntry): AuditEntry {
        val session = CurrentOperatorSession.current() ?: return entry
        return entry.copy(reason = reasonUnder(session, entry.reason))
    }

    companion object {
        /** `case <case>`, then `: <own reason>` when there is one (INV-Q-4521). */
        fun reasonUnder(
            session: OperatorSession,
            own: String?,
        ): String = "case ${session.case}" + own?.let { ": $it" }.orEmpty()
    }
}

/**
 * [OperatorSessionAuditWriter] over a delegate that is also an [AuditStager] (OutboxAuditWriter, LocalAuditWriter;
 * coordinator rulings INV-Q-4254 A, INV-Q-4277): it stays a stager, so an installation value's staged audit entry is
 * built by the delegate, with the same case prefix under a session.
 */
class OperatorSessionAuditStager(
    delegate: AuditWriter,
) : OperatorSessionAuditWriter(delegate),
    AuditStager {
    private val stager: AuditStager = requireNotNull(delegate as? AuditStager) { "the delegate must be an AuditStager" }

    override fun stage(
        entry: AuditEntry,
        context: EventContext,
    ): StagedAuditEntry = stager.stage(underSession(entry), context)
}

/**
 * Decorates every [AuditWriter] bean, a service's own included (INV-Q-4521): with [OperatorSessionAuditStager] when it
 * is also an [AuditStager], so the decorated bean keeps every port the delegate offers, else [OperatorSessionAuditWriter].
 */
class OperatorSessionAuditWriterPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        when {
            bean !is AuditWriter || bean is OperatorSessionAuditWriter -> bean
            bean is AuditStager -> OperatorSessionAuditStager(bean)
            else -> OperatorSessionAuditWriter(bean)
        }
}
