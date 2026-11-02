package com.dynokore.gst.submission.domain.readiness

/** The four item severities of the catalogue. */
enum class ItemSeverity { BLOCK, HOLD, WARN, INFO }

/** The VP-15 options of the entity (INV-REQ-1276); the keys are those of the catalogue row. */
enum class NotReadyOption {
    BLOCK_ONLY,
    ADD_WARN_CODES,
    ;

    companion object {
        /** The member named exactly [key]; any other string, including a different case, is refused. */
        fun fromKey(key: String): NotReadyOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown not-ready option key: '$key'")
    }
}

/** One item of a period with its code and severity. */
data class NotReadyItem(
    val code: String,
    val severity: ItemSeverity,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
    }
}

/** The VP-15 setting: the option and, for ADD_WARN_CODES, the named WARN codes. */
data class NotReadySetting(
    val option: NotReadyOption,
    val warnCodes: Set<String>,
) {
    init {
        when (option) {
            NotReadyOption.BLOCK_ONLY -> {
                require(warnCodes.isEmpty()) { "BLOCK_ONLY takes no warn codes" }
            }

            NotReadyOption.ADD_WARN_CODES -> {
                require(warnCodes.isNotEmpty()) { "ADD_WARN_CODES needs at least one code" }
                require(warnCodes.none { it.isBlank() }) { "warn codes must not be blank" }
            }
        }
    }
}

/** Why a setting is refused. */
enum class NotReadyRefusal { POINT_PLANNED, CODES_WITHOUT_OPTION, NO_CODES, CODE_NOT_WARN }

/** The answer of [NotReadyCondition.choose]. */
sealed interface NotReadyChoice {
    data class Accepted(
        val setting: NotReadySetting,
    ) : NotReadyChoice

    data class Refused(
        val reason: NotReadyRefusal,
    ) : NotReadyChoice
}

/** The pure rule of VP-15 (INV-REQ-1276): tighten-only, no setting removes a BLOCK item. */
object NotReadyCondition {
    val DEFAULT = NotReadySetting(NotReadyOption.BLOCK_ONLY, emptySet())

    /**
     * Validates an offered setting. [pointAvailable] and [severityOf] are the catalogue's facts,
     * passed by the caller; codes are compared by exact equality.
     */
    fun choose(
        option: NotReadyOption,
        warnCodes: Set<String>,
        pointAvailable: Boolean,
        severityOf: Map<String, ItemSeverity>,
    ): NotReadyChoice {
        val refusal =
            when {
                option == NotReadyOption.BLOCK_ONLY -> {
                    if (warnCodes.isNotEmpty()) NotReadyRefusal.CODES_WITHOUT_OPTION else null
                }

                !pointAvailable -> {
                    NotReadyRefusal.POINT_PLANNED
                }

                warnCodes.isEmpty() -> {
                    NotReadyRefusal.NO_CODES
                }

                warnCodes.any { severityOf[it] != ItemSeverity.WARN } -> {
                    NotReadyRefusal.CODE_NOT_WARN
                }

                else -> {
                    null
                }
            }
        return when {
            refusal != null -> NotReadyChoice.Refused(refusal)
            option == NotReadyOption.BLOCK_ONLY -> NotReadyChoice.Accepted(DEFAULT)
            else -> NotReadyChoice.Accepted(NotReadySetting(option, warnCodes.toSet()))
        }
    }

    /** True when any BLOCK item exists, or, under ADD_WARN_CODES, a WARN item with a named code. */
    fun isNotReady(
        setting: NotReadySetting,
        items: List<NotReadyItem>,
    ): Boolean =
        items.any { item ->
            item.severity == ItemSeverity.BLOCK ||
                (
                    setting.option == NotReadyOption.ADD_WARN_CODES &&
                        item.severity == ItemSeverity.WARN &&
                        item.code in setting.warnCodes
                )
        }
}
