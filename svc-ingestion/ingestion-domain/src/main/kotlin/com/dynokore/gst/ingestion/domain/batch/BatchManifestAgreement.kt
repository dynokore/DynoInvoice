package com.dynokore.gst.ingestion.domain.batch

import java.math.BigDecimal

/** The manifest declared in the batch envelope; null means absent or unreadable. */
data class BatchDeclaration(
    val count: Long?,
    val netTotal: BigDecimal?,
    val taxTotal: BigDecimal?,
)

/** The count and sums counted from the batch's transactions. */
data class BatchContents(
    val count: Long,
    val netTotal: BigDecimal,
    val taxTotal: BigDecimal,
)

enum class ManifestField { COUNT, NET_TOTAL, TAX_TOTAL }

enum class ManifestFault { MISSING, DIFFERS }

data class ManifestMismatch(
    val field: ManifestField,
    val fault: ManifestFault,
)

sealed interface BatchManifestVerdict {
    data object Agrees : BatchManifestVerdict

    data class Refused(
        val mismatches: List<ManifestMismatch>,
    ) : BatchManifestVerdict {
        val code: String = BatchManifestAgreement.ING_003
        val httpStatus: Int = BatchManifestAgreement.UNPROCESSABLE
    }
}

/** A batch whose contents disagree with its manifest is refused whole with ING-003 (INV-REQ-811, INV-AC-14105). */
object BatchManifestAgreement {
    const val ING_003 = "ING-003"
    const val UNPROCESSABLE = 422

    fun check(
        declaration: BatchDeclaration,
        contents: BatchContents,
    ): BatchManifestVerdict {
        val count = countFault(declaration.count, contents.count)
        val net = amountFault(declaration.netTotal, contents.netTotal)
        val tax = amountFault(declaration.taxTotal, contents.taxTotal)
        val mismatches =
            listOfNotNull(
                count?.let { ManifestMismatch(ManifestField.COUNT, it) },
                net?.let { ManifestMismatch(ManifestField.NET_TOTAL, it) },
                tax?.let { ManifestMismatch(ManifestField.TAX_TOTAL, it) },
            )
        return if (mismatches.isEmpty()) BatchManifestVerdict.Agrees else BatchManifestVerdict.Refused(mismatches)
    }

    private fun countFault(
        declared: Long?,
        actual: Long,
    ): ManifestFault? =
        if (declared == null) {
            ManifestFault.MISSING
        } else if (declared != actual) {
            ManifestFault.DIFFERS
        } else {
            null
        }

    private fun amountFault(
        declared: BigDecimal?,
        actual: BigDecimal,
    ): ManifestFault? =
        if (declared == null) {
            ManifestFault.MISSING
        } else if (declared.compareTo(actual) != 0) {
            ManifestFault.DIFFERS
        } else {
            null
        }
}
