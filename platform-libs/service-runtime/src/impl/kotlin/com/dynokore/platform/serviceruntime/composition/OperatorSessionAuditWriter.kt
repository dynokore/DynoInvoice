package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
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
class OperatorSessionAuditWriter(
    val delegate: AuditWriter,
) : AuditWriter {
    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope {
        val session = CurrentOperatorSession.current() ?: return delegate.record(entry, context)
        return delegate.record(entry.copy(reason = reasonUnder(session, entry.reason)), context)
    }

    companion object {
        /** `case <case>`, then `: <own reason>` when there is one (INV-Q-4521). */
        fun reasonUnder(
            session: OperatorSession,
            own: String?,
        ): String = "case ${session.case}" + (own?.let { ": $it" } ?: "")
    }
}

/** Decorates every [AuditWriter] bean, a service's own included, with [OperatorSessionAuditWriter] (INV-Q-4521). */
class OperatorSessionAuditWriterPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is AuditWriter && bean !is OperatorSessionAuditWriter) OperatorSessionAuditWriter(bean) else bean
}
