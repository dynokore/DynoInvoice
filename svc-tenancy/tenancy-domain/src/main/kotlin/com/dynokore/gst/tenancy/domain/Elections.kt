package com.dynokore.gst.tenancy.domain

/** The four go-live elections of an entity and the variation point each is stored under (INV-REQ-987). */
enum class ElectionKind(
    val variationPoint: String,
) {
    ISSUANCE_MODE("VP-25"),
    PURCHASE_DATE_BASIS("VP-22"),
    APPORTIONMENT_MODE("VP-23"),
    EXCHANGE_RATE_SOURCE("VP-24"),
}

/** The status of one election on a date (contract ElectionState.status). */
enum class ElectionStatus { ELECTED, OUTSTANDING, NOT_REQUIRED }

/** What the entity facts of the onboarding questionnaire say about a fact (owner answer INV-Q-9696 B). */
enum class FactAnswer { TRUE, FALSE, NOT_RECORDED }

/** The option keys of VP-25 (INV-REQ-1286). */
enum class IssuanceMode { RELAY, OBSERVE, NONE }

/**
 * The permissions the election operations ask the authority replica for (rulings INV-Q-9706, INV-Q-10025). Which
 * roles hold a key is decided by the catalogue (catalogue/role-permissions/role-permissions.yaml); no domain or
 * service code names a role (INV-Q-10027).
 */
object ElectionPermissions {
    const val MANAGE = "entity-config.manage"
    const val CONFIRM = "entity-config.confirm"
    const val APPROVE = "election-change.approve"

    /** The permission for `withdrawElectionChange`. */
    const val WITHDRAW = "election-change.withdraw"

    /** The permission for `listElectionChanges`. */
    const val LIST = "election-change.list"
}

object ElectionKeys {
    const val INVOICE_DATE = "INVOICE_DATE"
    const val POSTING_DATE = "POSTING_DATE"
    const val CLAIMABLE_PORTION = "CLAIMABLE_PORTION"
    const val ENTIRE_INVOICE = "ENTIRE_INVOICE"
    const val APPROVED_SOURCE = "APPROVED_SOURCE"

    /** The exempt-purchase election (INV-REQ-369); not yet in RECORDABLE, a later slice routes it. */
    const val EXEMPT_PURCHASE_CODES = "EXEMPT_PURCHASE_CODES"

    private val CODE_PATTERN = Regex("^[A-Z0-9][A-Z0-9_-]{0,39}$")
    private const val MAX_CODES = 50
    private const val MAX_BASIS = 500

    private val RECORDABLE =
        mapOf(
            INVOICE_DATE to ElectionKind.PURCHASE_DATE_BASIS,
            POSTING_DATE to ElectionKind.PURCHASE_DATE_BASIS,
            CLAIMABLE_PORTION to ElectionKind.APPORTIONMENT_MODE,
            ENTIRE_INVOICE to ElectionKind.APPORTIONMENT_MODE,
        )

    /** The election [key] belongs to, by exact equality; null for any other text (no trimming, no case folding). */
    fun recordableKind(key: String): ElectionKind? = RECORDABLE[key]

    /**
     * The refusal of an exempt-purchase election, or null when valid: 1 to 50 distinct codes each matching
     * `^[A-Z0-9][A-Z0-9_-]{0,39}$` (whole text, no trimming, no case folding), a basis of 1 to 500 characters that is
     * not blank (INV-REQ-369).
     */
    fun exemptPurchaseRefusal(
        codes: List<String>,
        basis: String,
    ): String? =
        when {
            codes.isEmpty() || codes.size > MAX_CODES -> "taxCodes must hold 1 to $MAX_CODES codes"
            codes.any { !CODE_PATTERN.matches(it) } -> "every tax code must match ${CODE_PATTERN.pattern}"
            codes.toSet().size != codes.size -> "tax codes must be distinct"
            basis.isBlank() || basis.length > MAX_BASIS -> "basis must be 1 to $MAX_BASIS characters and not blank"
            else -> null
        }
}

object ElectionRules {
    /**
     * The status of [kind] on a date: ELECTED when a value is in force; else NOT_REQUIRED only when the fact that
     * decides the election is recorded FALSE (INV-Q-9696 B), and OUTSTANDING otherwise, also for a fact not recorded
     * (fail closed). The issuance mode and the date basis are never NOT_REQUIRED.
     */
    fun status(
        kind: ElectionKind,
        elected: Boolean,
        fact: FactAnswer,
    ): ElectionStatus =
        when {
            elected -> ElectionStatus.ELECTED
            requiresFact(kind) && fact == FactAnswer.FALSE -> ElectionStatus.NOT_REQUIRED
            else -> ElectionStatus.OUTSTANDING
        }

    private fun requiresFact(kind: ElectionKind) =
        kind == ElectionKind.APPORTIONMENT_MODE || kind == ElectionKind.EXCHANGE_RATE_SOURCE
}
