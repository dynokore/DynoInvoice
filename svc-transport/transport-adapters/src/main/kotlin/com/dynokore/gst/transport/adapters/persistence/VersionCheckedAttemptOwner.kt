package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.application.AttemptOwner
import com.dynokore.gst.transport.application.AttemptOwnerRef
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.util.UUID

/**
 * An owner is found only when an attempt row of the Doc UUID AND the version exists (INV-REQ-585, INV-Q-9674: "a Doc
 * UUID or version with no attempt row" has no owner). `transport.attempt_owner` resolves tenant and entity from the Doc
 * UUID alone; this decorator then checks the version under the tenant it returned, so row-level security limits the
 * check to the owner's own rows.
 */
class VersionCheckedAttemptOwner(
    private val delegate: AttemptOwner,
    private val dsl: DSLContext,
    private val scope: TenantScope,
    private val runner: TransactionRunner,
    private val workloadPrincipal: String,
) : AttemptOwner {
    override fun ownerOf(doc: DocRef): AttemptOwnerRef? =
        delegate.ownerOf(doc)?.takeIf { owner ->
            scope.bind(TenantId(UUID.fromString(owner.tenant.value)), workloadPrincipal) {
                runner.inTransaction {
                    dsl.fetchExists(
                        SUBMISSION_ATTEMPT,
                        SUBMISSION_ATTEMPT.DOC_UUID
                            .eq(doc.docUuid)
                            .and(SUBMISSION_ATTEMPT.VERSION.eq(doc.version)),
                    )
                }
            }
        }
}
