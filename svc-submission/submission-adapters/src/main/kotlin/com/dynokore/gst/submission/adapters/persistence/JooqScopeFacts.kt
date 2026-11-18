package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.arrival.ScopeEntry
import com.dynokore.gst.submission.application.arrival.ScopeFacts
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.scope.NotSubmittableReason
import com.dynokore.gst.submission.domain.scope.ScopeVerdict
import com.dynokore.gst.submission.domain.scope.Verdict
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [ScopeFacts] over `submission.obligation_register_entry` (INV-T-24506): one select where tenant and Doc UUID match.
 * It opens no transaction and writes no row; forced row level security applies as well. A missing row is null. A kind,
 * verdict or reason that is not a member name throws [IllegalStateException] (fail closed, never a default).
 */
class JooqScopeFacts(
    private val dsl: DSLContext,
) : ScopeFacts {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun of(
        tenant: TenantId,
        docUuid: String,
    ): ScopeEntry? =
        dsl
            .select(e.ENTITY_REF, e.PERIOD_REF, e.KIND, e.SCOPE_VERDICT, e.NOT_SUBMITTABLE_REASON, e.PRECEDING_DOC_UUID)
            .from(e)
            .where(e.TENANT_ID.eq(tenant.value))
            .and(e.DOC_UUID.eq(docUuid))
            .fetchOne()
            ?.let { row ->
                val kind = checkNotNull(row.get(e.KIND))
                val verdict = row.get(e.SCOPE_VERDICT)
                val reason = row.get(e.NOT_SUBMITTABLE_REASON)
                ScopeEntry(
                    entityRef = checkNotNull(row.get(e.ENTITY_REF)),
                    periodRef = checkNotNull(row.get(e.PERIOD_REF)),
                    kind = checkNotNull(RowKind.entries.firstOrNull { it.name == kind }) { "Unmapped kind" },
                    verdict = verdictOf(verdict, reason),
                    precedingDocUuid = row.get(e.PRECEDING_DOC_UUID),
                )
            }

    private fun verdictOf(
        verdict: String?,
        reason: String?,
    ): Verdict? {
        if (verdict == null) {
            check(reason == null) { "A reason without a scope verdict" }
            return null
        }
        val scope = checkNotNull(ScopeVerdict.entries.firstOrNull { it.name == verdict }) { "Unmapped scope verdict" }
        val why =
            reason?.let { r ->
                checkNotNull(NotSubmittableReason.entries.firstOrNull { it.name == r }) { "Unmapped reason" }
            }
        return try {
            Verdict(scope, why)
        } catch (ex: IllegalArgumentException) {
            throw IllegalStateException("Contradictory scope verdict and reason", ex)
        }
    }
}
