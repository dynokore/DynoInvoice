package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.routing.UnresolvedInput
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId

/** The register entry of a document: the entity, the period and the kind. */
data class RegisteredDocument(
    val entityRef: String,
    val periodRef: String,
    val kind: RowKind,
)

/** Reads the obligation register entry of a Doc UUID; null when there is none (the normal case on main). */
interface RegisteredDocuments {
    fun find(
        tenant: TenantId,
        docUuid: String,
    ): RegisteredDocument?
}

/** Answers whether a period is filed. Runs in the caller's tenant transaction. */
interface FiledPeriods {
    fun isFiled(
        entityRef: String,
        periodRef: String,
    ): Boolean
}

/** Counts arrival facts that were unknown; the tags are bounded (no entity or period tag). */
interface ArrivalMetrics {
    fun unresolvedInput(input: UnresolvedInput)

    fun unknownRegisterEntry()
}

/** The plan of an arriving version: the lane, the codes to raise and the register keys when known. */
data class ArrivalPlan(
    val lane: Lane,
    val codes: List<String>,
    val entityRef: String?,
    val periodRef: String?,
)
