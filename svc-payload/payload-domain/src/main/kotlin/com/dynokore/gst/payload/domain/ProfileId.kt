package com.dynokore.gst.payload.domain

/** The direction of a document; the names equal the ingestion contract's `direction` values. */
enum class Direction {
    SALE,
    PURCHASE,
}

/** The specification family; the names equal the keys of the family variation point. */
enum class SpecificationFamily {
    PINT_SG,
    SG_PEPPOL_BIS,
}

/** The outcome of the Profile ID consistency check. */
sealed interface ProfileCheck {
    data object Consistent : ProfileCheck

    data class Mismatch(
        val expected: String,
        val actual: String,
    ) : ProfileCheck
}

/** The Profile ID of a payload (INV-REQ-465, INV-Q-6788 A), a pure function of direction and family. */
object ProfileId {
    private const val PINT_SG_BILLING = "urn:peppol:bis:billing"
    private const val PINT_SG_PAYABLES = "urn:peppol:bis:Payables"
    private const val BIS_BILLING = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0"
    private const val BIS_PAYABLES = "urn:fdc:peppol.eu:2017:poacc:Payables:01:1.0"

    fun expected(
        direction: Direction,
        family: SpecificationFamily,
    ): String =
        when (direction) {
            Direction.SALE -> {
                when (family) {
                    SpecificationFamily.PINT_SG -> PINT_SG_BILLING
                    SpecificationFamily.SG_PEPPOL_BIS -> BIS_BILLING
                }
            }

            Direction.PURCHASE -> {
                when (family) {
                    SpecificationFamily.PINT_SG -> PINT_SG_PAYABLES
                    SpecificationFamily.SG_PEPPOL_BIS -> BIS_PAYABLES
                }
            }
        }

    /** Exact string equality: case-sensitive, no trimming. */
    fun check(
        direction: Direction,
        family: SpecificationFamily,
        actual: String,
    ): ProfileCheck {
        val expected = expected(direction, family)
        return if (actual == expected) ProfileCheck.Consistent else ProfileCheck.Mismatch(expected, actual)
    }
}
