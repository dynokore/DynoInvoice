package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.IdentifierScheme

/** One mapped row of a counterparty import file (`CounterpartyImportRow` of the contract), as given. */
data class ImportRow(
    val counterpartyRef: String?,
    val uen: String?,
    val legalName: String?,
    val addressLine1: String?,
    val postalCode: String?,
)

/** A row refused at the dry run: its 1-based [row] number, the finding [code] and the [field] it names. */
data class RejectedImportRow(
    val row: Int,
    val code: String,
    val message: String,
    val field: String?,
)

/** Rows that cannot all be applied: the file rows with one UEN, or a row whose UEN another counterparty holds. */
data class ImportDuplicate(
    val uen: String,
    val rows: List<Int>,
    val existingCounterpartyRef: String?,
)

/** One field the apply would change on [counterpartyRef] (`oldValue` null when the counterparty is new). */
data class ImportChange(
    val uen: String,
    val field: String,
    val newValue: String,
    val counterpartyRef: String,
    val oldValue: String?,
)

/** A row the apply writes: its 1-based [row] number and the counterparty it is written to. */
data class ImportTarget(
    val row: Int,
    val party: PartyRef,
    val data: ImportRow,
)

data class ImportAnalysis(
    val rejected: List<RejectedImportRow>,
    val duplicates: List<ImportDuplicate>,
    val targets: List<ImportTarget>,
    val createCount: Int,
    val updateCount: Int,
    val unchangedCount: Int,
    val changes: List<ImportChange>,
) {
    /** The rows that are in neither [rejected] nor [duplicates] and have a target (INV-Q-17289). */
    val validCount: Int get() = targets.size
}

/** The held (current) state the analysis compares with; reads only. */
interface HeldCounterparties {
    /** The current version of the counterparty holding [uen] as its UEN or NA identifier, lowest reference first. */
    fun byUen(uen: String): CounterpartyVersion?

    fun current(party: PartyRef): CounterpartyVersion?
}

private const val MDM_UEN_ABSENT = "MDM-002"
private const val MDM_UEN_INVALID = "MDM-003"
private const val MDM_ADDRESS_INCOMPLETE = "MDM-006"
private const val MDM_NO_TARGET = "MDM-010"

/**
 * The dry run of a counterparty import (INV-Q-9793, INV-Q-17289), a pure function of the rows and the held records.
 * Per row: UEN absent (MDM-002, `/uen`), UEN refused by [UenFormat] (MDM-003, `/uen`, the literal `NA` is never
 * judged),
 * address line 1 or postal code missing (MDM-006). A row with such a finding is rejected and joins no other list.
 * The other rows form duplicate groups: file rows with the same UEN as held (no trimming), and rows whose UEN is held
 * by a counterparty other than the one their own `counterpartyRef` names; every row of a group is listed and none is
 * written. The target of a remaining row is its `counterpartyRef`, else the held counterparty whose UEN equals the
 * row's UEN exactly, else the row is rejected with MDM-010 at `/counterpartyRef`. No reference is ever generated.
 */
fun analyseImport(
    rows: List<ImportRow>,
    held: HeldCounterparties,
): ImportAnalysis {
    val rejected = mutableListOf<RejectedImportRow>()
    val clean = mutableListOf<Pair<Int, ImportRow>>()
    rows.forEachIndexed { index, row ->
        val number = index + 1
        val found = rowFindings(number, row)
        if (found.isEmpty()) clean += number to row else rejected += found
    }
    val duplicates = duplicateGroups(clean, held)
    val duplicateRows = duplicates.flatMap { it.rows }.toSet()
    val targets = mutableListOf<ImportTarget>()
    clean.filter { it.first !in duplicateRows }.forEach { (number, row) ->
        val ref = row.counterpartyRef ?: held.byUen(row.uen.orEmpty())?.partyRef?.value
        if (ref == null) {
            rejected +=
                RejectedImportRow(
                    number,
                    MDM_NO_TARGET,
                    "no counterparty reference and no held UEN match",
                    "/counterpartyRef",
                )
        } else {
            targets += ImportTarget(number, PartyRef(ref), row)
        }
    }
    return preview(rejected.sortedBy { it.row }, duplicates.sortedBy { it.rows.first() }, targets, held)
}

private fun duplicateGroups(
    clean: List<Pair<Int, ImportRow>>,
    held: HeldCounterparties,
): List<ImportDuplicate> =
    clean.groupBy({ it.second.uen.orEmpty() }, { it }).mapNotNull { (uen, group) ->
        val heldRef = held.byUen(uen)?.partyRef?.value
        val foreign =
            group.filter { (_, r) ->
                heldRef != null && r.counterpartyRef != null && r.counterpartyRef != heldRef
            }
        val members = if (group.size > 1) group else foreign
        if (members.isEmpty()) null else ImportDuplicate(uen, members.map { it.first }, heldRef)
    }

private fun rowFindings(
    number: Int,
    row: ImportRow,
): List<RejectedImportRow> {
    val found = mutableListOf<RejectedImportRow>()
    val uen = row.uen
    if (uen.isNullOrEmpty()) {
        found += RejectedImportRow(number, MDM_UEN_ABSENT, "the UEN is absent", "/uen")
    } else if (uen != NA && !UenFormat.accepts(uen)) {
        found += RejectedImportRow(number, MDM_UEN_INVALID, "the UEN has no valid form", "/uen")
    }
    if (row.addressLine1.isNullOrBlank()) {
        found += RejectedImportRow(number, MDM_ADDRESS_INCOMPLETE, "address line 1 is missing", "/addressLine1")
    }
    if (row.postalCode.isNullOrBlank()) {
        found += RejectedImportRow(number, MDM_ADDRESS_INCOMPLETE, "the postal code is missing", "/postalCode")
    }
    return found
}

private fun preview(
    rejected: List<RejectedImportRow>,
    duplicates: List<ImportDuplicate>,
    targets: List<ImportTarget>,
    held: HeldCounterparties,
): ImportAnalysis {
    var create = 0
    var update = 0
    var unchanged = 0
    val changes = mutableListOf<ImportChange>()
    targets.forEach { t ->
        val before = held.current(t.party)?.content
        val uen = t.data.uen.orEmpty()
        val fields =
            listOf(
                Triple("uen", before?.identifiers?.firstOrNull { it.scheme.isUenOrNone() }?.value, uen),
                Triple("legalName", before?.legalName, t.data.legalName),
                Triple("addressLine1", before?.address?.line1, t.data.addressLine1),
                Triple("postalCode", before?.address?.postalCode, t.data.postalCode),
            ).filter { (_, old, new) -> new != null && new != old }
        fields.forEach { (field, old, new) ->
            changes += ImportChange(uen, field, checkNotNull(new), t.party.value, old)
        }
        when {
            before == null -> create++
            fields.isEmpty() -> unchanged++
            else -> update++
        }
    }
    return ImportAnalysis(rejected, duplicates, targets, create, update, unchanged, changes)
}

private fun IdentifierScheme.isUenOrNone() = this == IdentifierScheme.UEN || this == IdentifierScheme.NONE
