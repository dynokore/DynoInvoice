package com.dynokore.gst.masterdata.domain.counterparty

/**
 * The UEN format rule behind MDM-003 (INV-Q-17217 A), in one place (ARC-19). A UEN is accepted only if the
 * whole string, as given, equals one of:
 *  - `[0-9]{8}[A-Z]`: a business registered with ACRA;
 *  - `[0-9]{9}[A-Z]`: a local company (4-digit year and 5 digits);
 *  - `[TSR][0-9]{2}[A-Z]{2}[0-9]{4}[A-Z]`: another entity.
 * ASCII only, no trimming, no case folding, no year check and no check-letter algorithm: no ACRA-published
 * algorithm can be cited, so the final character is only checked to be A to Z. The literal `NA` and an absent
 * UEN are never judged here: `NA` is the NA classification (scheme NONE) and an absent UEN is MDM-002.
 */
@JvmInline
value class UenFormat private constructor(
    val value: String,
) {
    companion object {
        private val BUSINESS = Regex("[0-9]{8}[A-Z]")
        private val LOCAL_COMPANY = Regex("[0-9]{9}[A-Z]")
        private val OTHER_ENTITY = Regex("[TSR][0-9]{2}[A-Z]{2}[0-9]{4}[A-Z]")

        /** True when [candidate], exactly as given, has one of the three UEN forms. */
        fun accepts(candidate: String): Boolean =
            BUSINESS.matches(candidate) || LOCAL_COMPANY.matches(candidate) || OTHER_ENTITY.matches(candidate)

        /** The UEN when [candidate] has one of the three forms, else null. */
        fun parse(candidate: String): UenFormat? = if (accepts(candidate)) UenFormat(candidate) else null
    }
}
