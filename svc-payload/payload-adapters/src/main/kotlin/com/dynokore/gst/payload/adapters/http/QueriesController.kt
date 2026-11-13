package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.version.GetLatestDocumentVersion
import com.dynokore.gst.payload.application.version.LatestVersionResult
import com.dynokore.gst.payload.contract.api.QueriesApi
import com.dynokore.gst.payload.contract.model.LatestDocumentVersion
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The query operations of the payload contract (TPL-08). `getLatestDocumentVersion` (INV-REQ-500) answers through
 * [GetLatestDocumentVersion]; every other operation answers 501 until the task named for it in `operations.tsv`
 * overrides its method: the generated interface's default is NOT_IMPLEMENTED. The acting user is the `sub` of the
 * verified end-user principal, never a request field; refusals are the API-20 envelopes (SRT-005, SRT-004,
 * UNAUTHENTICATED) rendered as in [ValidationOverrideController], and a 403 body carries no version.
 */
@RestController
open class QueriesController(
    private val latest: GetLatestDocumentVersion,
    private val transactions: TransactionRunner,
    private val http: ObjectProvider<HttpServletRequest>,
) : QueriesApi {
    override fun getLatestDocumentVersion(docUuid: UUID): ResponseEntity<LatestDocumentVersion> {
        val principal = principalOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        return when (val result = transactions.inTransaction { latest.execute(principal.actor, docUuid) }) {
            is LatestVersionResult.Found -> ResponseEntity.ok(LatestDocumentVersion(result.docUuid, result.version))
            LatestVersionResult.NotFound -> OverrideHttp.problem(ClosedErrorCode.NOT_FOUND)
            LatestVersionResult.Forbidden -> OverrideHttp.problem(ClosedErrorCode.ACCESS_DENIED)
        }
    }

    private fun principalOrNull(): EndUserPrincipal? =
        http.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
}
