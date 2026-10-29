package com.dynokore.gst.payload.domain

/** A connection test run in one specification family (INV-REQ-2467 behavior 3). */
data class ConnectionTestRun(
    val family: SpecificationFamily,
    val passed: Boolean,
)

/** Why a connection may not be activated. */
enum class ActivationRefusal {
    TEST_NOT_RUN,
    TESTED_IN_OTHER_FAMILY,
    TEST_FAILED,
}

/** Whether a connection may be activated. */
sealed interface ActivationVerdict {
    data object MayActivate : ActivationVerdict

    data class Refused(
        val reason: ActivationRefusal,
    ) : ActivationVerdict
}

/** The family in force for a connection (VP-71); electing is never refused, the default is catalogue data. */
object FamilyElection {
    fun effective(
        elected: SpecificationFamily?,
        default: SpecificationFamily,
    ): SpecificationFamily = elected ?: default
}

/** The activation gate of a connection; fails closed (NIST SP 800-53 SC-24). */
object ConnectionActivation {
    fun decide(
        effective: SpecificationFamily,
        test: ConnectionTestRun?,
    ): ActivationVerdict =
        when {
            test == null -> ActivationVerdict.Refused(ActivationRefusal.TEST_NOT_RUN)
            test.family != effective -> ActivationVerdict.Refused(ActivationRefusal.TESTED_IN_OTHER_FAMILY)
            !test.passed -> ActivationVerdict.Refused(ActivationRefusal.TEST_FAILED)
            else -> ActivationVerdict.MayActivate
        }
}
