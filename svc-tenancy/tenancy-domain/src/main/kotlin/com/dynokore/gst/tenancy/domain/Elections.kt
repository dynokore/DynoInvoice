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

/** The roles the election rules name (INV-REQ-323, INV-REQ-505, INV-Q-9704 A). */
object ElectionRoles {
    const val ENTITY_ADMIN = "entity.admin"
    const val TENANT_ADMIN = "tenant.admin"
    const val TAX_APPROVER = "tax.approver"
}

/**
 * The permissions the election operations ask the authority replica for (rulings INV-Q-9706, INV-Q-10025): MANAGE is
 * held by `entity.admin` only, CONFIRM by `entity.admin`, `tenant.admin` and `tax.approver`
 * (catalogue/role-permissions/role-permissions.yaml).
 */
object ElectionPermissions {
    const val MANAGE = "entity-config.manage"
    const val CONFIRM = "entity-config.confirm"
    const val APPROVE = "election-change.approve"
}

object ElectionKeys {
    const val INVOICE_DATE = "INVOICE_DATE"
    const val POSTING_DATE = "POSTING_DATE"
    const val CLAIMABLE_PORTION = "CLAIMABLE_PORTION"
    const val ENTIRE_INVOICE = "ENTIRE_INVOICE"
    const val APPROVED_SOURCE = "APPROVED_SOURCE"

    private val RECORDABLE =
        mapOf(
            INVOICE_DATE to ElectionKind.PURCHASE_DATE_BASIS,
            POSTING_DATE to ElectionKind.PURCHASE_DATE_BASIS,
            CLAIMABLE_PORTION to ElectionKind.APPORTIONMENT_MODE,
            ENTIRE_INVOICE to ElectionKind.APPORTIONMENT_MODE,
        )

    /** The election [key] belongs to, by exact equality; null for any other text (no trimming, no case folding). */
    fun recordableKind(key: String): ElectionKind? = RECORDABLE[key]
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
