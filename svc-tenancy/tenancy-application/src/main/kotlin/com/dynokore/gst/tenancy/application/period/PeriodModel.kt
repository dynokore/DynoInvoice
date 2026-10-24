package com.dynokore.gst.tenancy.application.period

import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.NonBusinessDayRule
import com.dynokore.platform.calendarandperiods.PeriodKind
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/** The state of one entry of the filing-frequency history (INV-REQ-705, INV-Q-9727 A). */
enum class HistoryState { ACTIVE, PENDING, SUPERSEDED }

/** One effective-dated entry of an entity's filing-frequency history: the frequency code and the IRAS approval. */
data class FrequencyEntry(
    val id: UUID,
    val effectiveFrom: LocalDate,
    val frequency: String,
    val irasApprovalReference: String,
    val state: HistoryState,
)

/** Whether `confirmPeriods` has confirmed a period record (INV-REQ-84 b5). */
enum class ConfirmationState { UNCONFIRMED, CONFIRMED }

/**
 * A period record as stored (INV-REQ-83, INV-REQ-84, INV-REQ-707): explicit start and end, return form, filing due
 * date, the kinds that make it irregular and its confirmation state. Membership of a date is the test against the
 * stored start and end, never a computation from a frequency (INV-AC-9546).
 */
data class StoredPeriod(
    val periodRef: String,
    val start: LocalDate,
    val end: LocalDate,
    val returnForm: String,
    val dueDate: LocalDate,
    val kinds: Set<PeriodKind>,
    val confirmation: ConfirmationState = ConfirmationState.UNCONFIRMED,
) {
    /** True for a first, final or transitional period. */
    val irregular: Boolean get() = kinds.isNotEmpty()

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    /** The same period by every field the generator decides (the confirmation state is the operator's). */
    fun sameGenerated(other: StoredPeriod): Boolean =
        start == other.start &&
            end == other.end &&
            returnForm == other.returnForm &&
            dueDate == other.dueDate &&
            kinds == other.kinds

    companion object {
        /**
         * The opaque reference tenancy-svc issues for a period (API-11): the same for the same entity and start date,
         * so a proposal and the record it becomes carry one reference (INV-Q-9727 A).
         */
        fun refOf(
            entityId: UUID,
            start: LocalDate,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("period:$entityId:$start".toByteArray())
            return "PRD-" + HexFormat.of().formatHex(digest, 0, REF_BYTES)
        }

        private const val REF_BYTES = 10
    }
}

/** The review items a frequency change raises (INV-REQ-708). */
enum class RaisedCode(
    val code: String,
) {
    PER_004("PER-004"),
    PER_005("PER-005"),
}

enum class ItemState { OPEN, RESOLVED, SUPERSEDED }

data class RaisedItem(
    val historyId: UUID,
    val code: RaisedCode,
    val state: ItemState,
)

/** The filing-frequency history of the entities of the bound tenant; every method runs in the caller's transaction. */
interface FrequencyHistoryStore {
    /** Every entry of the entity in every state, oldest effective date first. */
    fun entries(entityId: UUID): List<FrequencyEntry>

    fun insert(
        entityId: UUID,
        entry: FrequencyEntry,
    )

    fun setState(
        entityId: UUID,
        id: UUID,
        state: HistoryState,
    )
}

/** The proposals of frequency changes: the periods computed apart from the period records and the raised items. */
interface ProposalStore {
    fun proposedPeriods(
        entityId: UUID,
        historyId: UUID,
    ): List<StoredPeriod>

    fun insertProposed(
        entityId: UUID,
        historyId: UUID,
        periods: List<StoredPeriod>,
    )

    fun items(entityId: UUID): List<RaisedItem>

    fun raise(
        entityId: UUID,
        historyId: UUID,
        code: RaisedCode,
    )

    fun moveItems(
        entityId: UUID,
        historyId: UUID,
        to: ItemState,
    )
}

/** The three stores a frequency change reads and writes, in the caller's transaction. */
class PeriodStores(
    val history: FrequencyHistoryStore,
    val proposals: ProposalStore,
    val periods: PeriodRecordStore,
)

/** The period records of the entities of the bound tenant; a superseded record is kept and never listed as in force. */
interface PeriodRecordStore {
    /** The records in force, earliest start first. */
    fun inForce(entityId: UUID): List<StoredPeriod>

    fun insert(
        entityId: UUID,
        period: StoredPeriod,
    )

    /** Marks every record in force that starts on or after [start] as superseded (never deleted). */
    fun supersedeFrom(
        entityId: UUID,
        start: LocalDate,
    )

    fun confirm(
        entityId: UUID,
        periodRef: String,
    )
}

/** The calendar pack in force on a date; null when none is known, and then every operation that needs it refuses. */
fun interface CalendarPackSource {
    fun inForce(on: LocalDate): CalendarPack?
}

/** VP-62 resolved for the entity on a date (INV-REQ-87): whether a due date on a non-business day moves. */
fun interface NonBusinessDayRuleReader {
    fun resolve(
        groupRef: String,
        entityRef: String,
        on: LocalDate,
    ): NonBusinessDayRule
}

/** One FilingFrequencyChanged to publish through the outbox in the confirming transaction (INV-Q-7391). */
data class FilingFrequencyChange(
    val entityRef: String,
    val oldFrequency: String,
    val newFrequency: String,
    /** The `periodRef` of the stored record starting on the first day the new frequency governs (open span). */
    val fromPeriod: String,
)

interface FilingFrequencyChangePublisher {
    fun publish(change: FilingFrequencyChange)
}
