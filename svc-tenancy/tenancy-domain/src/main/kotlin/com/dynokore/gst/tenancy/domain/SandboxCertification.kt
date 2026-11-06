package com.dynokore.gst.tenancy.domain

/** The result the sandbox certification runner reports (INV-Q-16403). */
enum class CertificationOutcome { PASSED, FAILED }

/** The stale state of the certification of one entity (INV-REQ-1135). */
enum class CertificationStatus { CURRENT, STALE }

/**
 * The closed set of re-certification triggers (INV-Q-16393). A text is a trigger only by exact equality with a name;
 * a look-alike such as `NEW_TRAINS` or `new_train` is none.
 */
enum class StaleTrigger {
    NEW_TRAIN,
    NEW_PACK,
    ISSUANCE_MODE,
    IDENTITY_TRIPLE,
    SCHEME_HANDLER,
    ;

    companion object {
        fun parse(text: String): StaleTrigger? = entries.firstOrNull { it.name == text }
    }
}

/** The permission key that guards `certifySandbox` (INV-Q-16389); never a role name (INV-Q-10027). */
object SandboxCertificationPermissions {
    const val RECORD = "sandbox-certification.record"
}

/** The rule on `failedScenarios` of a certification request (INV-Q-16403). */
object CertificationRules {
    const val SCENARIO_MAX_LENGTH = 128

    /** @return the reason the combination is refused, or null when it is accepted. */
    fun refusal(
        outcome: CertificationOutcome,
        failedScenarios: List<String>?,
    ): String? =
        when (outcome) {
            CertificationOutcome.FAILED -> {
                failedRefusal(failedScenarios)
            }

            CertificationOutcome.PASSED -> {
                if (failedScenarios != null) "failedScenarios is forbidden when the outcome is PASSED" else null
            }
        }

    private fun failedRefusal(failedScenarios: List<String>?): String? =
        when {
            failedScenarios.isNullOrEmpty() -> {
                "failedScenarios is required and non-empty when the outcome is FAILED"
            }

            failedScenarios.any { it.length !in 1..SCENARIO_MAX_LENGTH } -> {
                "a failed scenario has 1 to $SCENARIO_MAX_LENGTH characters"
            }

            else -> {
                null
            }
        }
}
