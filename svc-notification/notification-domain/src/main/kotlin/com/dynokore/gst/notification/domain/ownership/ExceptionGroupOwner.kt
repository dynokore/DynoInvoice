package com.dynokore.gst.notification.domain.ownership

/** VP-33 option keys (INV-REQ-1294): who owns each exception group. */
enum class OwnerOption {
    DEFAULT_OWNER,
    ROUTING_RULES,
    ;

    companion object {
        /** The member named exactly [key]; any other string (lower case, look-alike, empty) is refused. */
        fun fromKey(key: String): OwnerOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("unknown owner option key: '$key'")
    }
}

/** An exception group as the owner rule sees it. */
data class ExceptionGroup(
    val entityId: String,
    val code: String,
    val counterpartyId: String?,
) {
    init {
        require(entityId.isNotBlank()) { "entityId must not be blank" }
        require(code.isNotBlank()) { "code must not be blank" }
        require(counterpartyId == null || counterpartyId.isNotBlank()) { "counterpartyId must be null or not blank" }
    }
}

/** A routing rule: every non-null selector must equal the group's value; the first matching rule wins. */
data class OwnerRule(
    val entityId: String?,
    val code: String?,
    val counterpartyId: String?,
    val team: String,
) {
    init {
        require(entityId != null || code != null || counterpartyId != null) { "a rule needs at least one selector" }
        require(entityId == null || entityId.isNotBlank()) { "entityId selector must not be blank" }
        require(code == null || code.isNotBlank()) { "code selector must not be blank" }
        require(counterpartyId == null || counterpartyId.isNotBlank()) { "counterpartyId selector must not be blank" }
        require(team.isNotBlank()) { "team must not be blank" }
    }
}

/** The default owner of each exception code (INV-NOTE-3 section 21.2), passed in as data. */
class CodeOwners(
    val byCode: Map<String, String>,
) {
    init {
        require(byCode.all { (k, v) -> k.isNotBlank() && v.isNotBlank() }) { "code and owner must not be blank" }
    }
}

enum class OwnerRefusal { POINT_PLANNED, RULES_WITHOUT_OPTION, DUPLICATE_RULE, UNKNOWN_CODE }

sealed interface OwnerSettingCheck {
    data object Valid : OwnerSettingCheck

    data class Refused(
        val reason: OwnerRefusal,
    ) : OwnerSettingCheck
}

/** Pure VP-33 rule (INV-AC-16255): reads no role, clock or store. */
object ExceptionGroupOwner {
    fun check(
        option: OwnerOption,
        rules: List<OwnerRule>,
        owners: CodeOwners,
        pointAvailable: Boolean,
    ): OwnerSettingCheck {
        val selectors = rules.map { Triple(it.entityId, it.code, it.counterpartyId) }
        val refusal =
            when {
                option == OwnerOption.ROUTING_RULES && !pointAvailable -> OwnerRefusal.POINT_PLANNED
                option == OwnerOption.DEFAULT_OWNER && rules.isNotEmpty() -> OwnerRefusal.RULES_WITHOUT_OPTION
                selectors.toSet().size != selectors.size -> OwnerRefusal.DUPLICATE_RULE
                rules.any { it.code != null && it.code !in owners.byCode } -> OwnerRefusal.UNKNOWN_CODE
                else -> null
            }
        return if (refusal == null) OwnerSettingCheck.Valid else OwnerSettingCheck.Refused(refusal)
    }

    fun ownerOf(
        group: ExceptionGroup,
        option: OwnerOption,
        rules: List<OwnerRule>,
        owners: CodeOwners,
    ): String {
        val default =
            owners.byCode[group.code]
                ?: throw IllegalArgumentException("no default owner for code '${group.code}'")
        if (option == OwnerOption.DEFAULT_OWNER) return default
        return rules.firstOrNull { matches(it, group) }?.team ?: default
    }

    private fun matches(
        rule: OwnerRule,
        group: ExceptionGroup,
    ): Boolean =
        (rule.entityId == null || rule.entityId == group.entityId) &&
            (rule.code == null || rule.code == group.code) &&
            (rule.counterpartyId == null || rule.counterpartyId == group.counterpartyId)
}
