package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.application.ImportFailure
import com.dynokore.gst.rules.application.ImportRegulatoryPackResult
import com.dynokore.gst.rules.application.ImportStatus
import com.dynokore.gst.rules.application.ImportStatusAnswer
import com.dynokore.gst.rules.contract.model.RegulatoryPack
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode

/**
 * The answers of `importRegulatoryPack` and `getRegulatoryPack` against the pinned contract: a refused signature,
 * engine range or content is the 422-class error of its catalogue code (DEP-002, DEP-003, RUL-005; the contract lists
 * them in x-error-codes), a denied permission is 403 SRT-004, a bundle that is not in the pack source or an unknown
 * pack is 404 SRT-005, missing trust anchors or engine version is 503 SRT-013, and a pack version with another
 * manifest is 409 SRT-008. Every refusal changes nothing but the import's own record.
 */
object RegulatoryPackImportMapping {
    private const val DENIED_DETAIL = "the import is not permitted"
    private const val UNPROCESSABLE = 422

    fun refusalOf(result: ImportRegulatoryPackResult): MappingRefusal? =
        when (result) {
            ImportRegulatoryPackResult.Staged -> {
                null
            }

            is ImportRegulatoryPackResult.Failed -> {
                failed(result.failure, result.detail)
            }

            ImportRegulatoryPackResult.Denied -> {
                closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)
            }

            ImportRegulatoryPackResult.BundleNotFound -> {
                closed(ClosedErrorCode.NOT_FOUND, null)
            }

            ImportRegulatoryPackResult.Unavailable -> {
                unavailable("the import cannot be decided")
            }

            ImportRegulatoryPackResult.Conflict -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the pack version exists with another manifest")
            }
        }

    fun refusalOf(answer: ImportStatusAnswer): MappingRefusal? =
        when (answer) {
            is ImportStatusAnswer.Found -> null
            ImportStatusAnswer.NotFound -> closed(ClosedErrorCode.NOT_FOUND, null)
            ImportStatusAnswer.Denied -> closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)
        }

    fun packOf(
        packType: String,
        version: String,
        status: ImportStatus,
    ) = RegulatoryPack(
        packType = packType,
        version = version,
        status = RegulatoryPack.Status.forValue(status.status),
        failureCode = status.failureCode,
    )

    private fun failed(
        failure: ImportFailure,
        detail: String?,
    ) = MappingRefusal(
        UNPROCESSABLE,
        failure.code,
        when (failure) {
            ImportFailure.SIGNATURE -> "Pack signature verification failed"
            ImportFailure.ENGINE -> "Pack requires a newer engine version"
            ImportFailure.CONTENTS -> "Pack contents do not load"
        },
        detail,
    )

    private fun closed(
        code: ClosedErrorCode,
        detail: String?,
    ) = MappingRefusal(code.httpStatus, code.code, code.title, detail)
}
