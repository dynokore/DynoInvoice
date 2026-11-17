package com.dynokore.gst.access.domain.consultant

import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import java.time.LocalDate

enum class ConsultantRequestStatus { PENDING, APPROVED, DENIED }

/** What the operator asks for (INV-REQ-908): the repeat key of the contract is (tenant, identity, go-live date). */
data class ConsultantGrantAsk(
    val tenant: TenantRef,
    val identity: IdentityRef,
    val goLiveDate: LocalDate,
    val requestedBy: IdentityRef,
)

/**
 * A stored consultant grant request. [decidedBy] is a user ID, never a role. An APPROVED request names the grant that
 * was written and its last day boundary [validTo]; any other status names neither.
 */
data class ConsultantGrantRecord(
    val ref: String,
    val ask: ConsultantGrantAsk,
    val status: ConsultantRequestStatus,
    val decidedBy: IdentityRef?,
    val grant: GrantRef?,
    val validTo: LocalDate?,
    val version: Long,
) {
    init {
        require((status == ConsultantRequestStatus.APPROVED) == (grant != null && validTo != null)) {
            "only an approved request names its grant and validTo"
        }
        require((status == ConsultantRequestStatus.PENDING) == (decidedBy == null)) {
            "only a decided request names who decided it"
        }
    }

    fun approved(
        by: IdentityRef,
        grantRef: GrantRef,
        until: LocalDate,
    ) = copy(
        status = ConsultantRequestStatus.APPROVED,
        decidedBy = by,
        grant = grantRef,
        validTo = until,
        version = version + 1,
    )

    fun denied(by: IdentityRef) = copy(status = ConsultantRequestStatus.DENIED, decidedBy = by, version = version + 1)
}
