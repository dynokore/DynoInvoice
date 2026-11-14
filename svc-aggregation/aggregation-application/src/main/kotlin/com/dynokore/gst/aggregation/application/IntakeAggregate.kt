package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateLine
import com.dynokore.gst.aggregation.domain.AggregateLines
import com.dynokore.gst.aggregation.domain.AggregateNumber
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.NumberingResult
import com.dynokore.gst.aggregation.domain.WindowState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Registered short codes from the structure replica (INV-REQ-436 item 3); null: no usable code for the node. */
interface ShortCodes {
    fun entityCode(entityRef: String): String?

    fun businessUnitCode(businessUnitRef: String): String?
}

/** Thrown by [AggregateRepository.store] when the triple (entityRef, sourceKey, sourceRevision) is already stored. */
class DuplicateIntakeKey(
    message: String = "the intake key is already stored",
) : RuntimeException(message)

interface AggregateRepository {
    fun findByIntakeKey(
        entityRef: String,
        sourceKey: String,
        sourceRevision: Long,
    ): StoredAggregate?

    fun highestRevision(
        entityRef: String,
        sourceKey: String,
    ): Long?

    fun nextSequence(): Long

    /** @throws DuplicateIntakeKey when the triple is already stored. */
    fun store(aggregate: NewAggregate): StoredAggregate
}

data class IntakeCommand(
    val entityRef: String,
    val businessUnitRef: String,
    val aggregateType: AggregateType,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val sourceKey: String,
    val sourceRevision: Long,
    val lines: List<AggregateLine> = emptyList(),
)

data class NewAggregate(
    val entityRef: String,
    val businessUnitRef: String,
    val aggregateType: AggregateType,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val sourceKey: String,
    val sourceRevision: Long,
    val sequence: Long,
    val entityCode: String,
    val businessUnitCode: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val transactionDate: LocalDate,
    val windowStart: LocalDate? = null,
    val windowEnd: LocalDate? = null,
    val windowState: WindowState? = null,
    val lines: List<AggregateLine> = emptyList(),
    val isSupplementary: Boolean = false,
)

data class StoredAggregate(
    val aggregate: NewAggregate,
    val aggregateId: UUID,
    /** Null until the aggregate is transmitted; written in production by INV-T-4980, only read here. */
    val transmittedAt: Instant? = null,
    /** Whether AggregateReady was published for the aggregate. */
    val handedOn: Boolean = false,
)

/**
 * The revision side of the aggregate store (INV-REQ-434 item 4), kept apart from [AggregateRepository] so the intake
 * of a first revision needs none of it.
 */
interface AggregateRevisions {
    /** The aggregate of the highest revision recorded for the entity and source key (never one that is superseded). */
    fun current(
        entityRef: String,
        sourceKey: String,
    ): StoredAggregate?

    /**
     * The lines of every aggregate of the entity and source key that is not superseded (originals and supplementaries),
     * except the aggregate [excluding]; credit-note aggregates are not part of the sum.
     */
    fun recordedLines(
        entityRef: String,
        sourceKey: String,
        excluding: UUID? = null,
    ): List<AggregateLine>

    /**
     * Stores [replacement] and marks [old] superseded by it in one transaction; the row of [old] is not deleted.
     * @throws DuplicateIntakeKey when the triple of [replacement] is already stored.
     */
    fun replace(
        old: StoredAggregate,
        replacement: NewAggregate,
    ): StoredAggregate
}

/** Records a revision that stored no aggregate (zero difference), against the aggregate it was compared with. */
interface SeenRevisions {
    fun recordSeen(
        compared: StoredAggregate,
        sourceRevision: Long,
    )
}

/** A short code that is null or does not match `^[A-Z0-9]{1,10}$` makes the number unbuildable. */
enum class NotBuildableReason {
    ENTITY_CODE,
    BUSINESS_UNIT_CODE,
}

sealed interface IntakeOutcome {
    data class Created(
        val aggregate: StoredAggregate,
    ) : IntakeOutcome

    data class Replayed(
        val aggregate: StoredAggregate,
    ) : IntakeOutcome

    data class SourceRevisionLower(
        val highestRevision: Long,
    ) : IntakeOutcome

    /** The window [periodStart, periodEnd] is not inside one prescribed period (ING-018); nothing is stored. */
    data object CrossesPeriodBoundary : IntakeOutcome

    /** A higher revision replaced an aggregate that was not transmitted; the number is the replaced one's. */
    data class Replaced(
        val aggregate: StoredAggregate,
        val replacedId: UUID,
    ) : IntakeOutcome

    /** A higher revision over a transmitted aggregate: a supplementary aggregate carries the difference. */
    data class Supplemented(
        val aggregate: StoredAggregate,
    ) : IntakeOutcome

    /** A higher revision whose per-code difference is zero: nothing is stored, [aggregate] is the one compared. */
    data class Unchanged(
        val aggregate: StoredAggregate,
    ) : IntakeOutcome

    data class NumberNotBuildable(
        val reason: NotBuildableReason,
    ) : IntakeOutcome
}

/**
 * Idempotent intake of one aggregate (INV-REQ-427, INV-REQ-434 items 1, 2, 3, 5). Replay is matched on the triple
 * (entityRef, sourceKey, sourceRevision); a replay returns the stored aggregate and reads neither the short codes nor
 * the sequence, so the number never changes (INV-REQ-436 item 3).
 */
@Suppress("LongParameterList")
class IntakeAggregate(
    private val shortCodes: ShortCodes,
    private val repository: AggregateRepository,
    private val windows: WindowRepository? = null,
    private val cadences: CadenceSource? = null,
    private val periods: PeriodBoundarySource? = null,
    private val revisions: AggregateRevisions? = null,
    private val seen: SeenRevisions? = null,
) {
    init {
        require((windows == null) == (cadences == null)) { "windows and cadences are set together or not at all" }
    }

    fun execute(command: IntakeCommand): IntakeOutcome =
        alreadyKnown(command) ?: when {
            crossesBoundary(command) -> {
                IntakeOutcome.CrossesPeriodBoundary
            }

            else -> {
                revisions?.current(command.entityRef, command.sourceKey)?.let { revise(command, it) }
                    ?: create(command)
            }
        }

    /**
     * Higher revision over a recorded aggregate (INV-REQ-434 item 4). Neither transmitted nor handed on: the new
     * revision replaces it and keeps its number. Otherwise it is never amended; a supplementary aggregate with the next
     * sequence and a new number carries the per-code difference to the aggregates of the key that are not superseded;
     * a zero difference stores nothing and answers the aggregate compared.
     */
    private fun revise(
        command: IntakeCommand,
        current: StoredAggregate,
    ): IntakeOutcome =
        when {
            current.transmittedAt != null || current.handedOn -> supplement(command, current)
            else -> replace(command, current)
        }

    private fun replace(
        command: IntakeCommand,
        current: StoredAggregate,
    ): IntakeOutcome {
        val store = checkNotNull(revisions)
        val old = current.aggregate
        val lines =
            if (old.isSupplementary) {
                AggregateLines.difference(
                    command.lines,
                    store.recordedLines(command.entityRef, command.sourceKey, current.aggregateId),
                )
            } else {
                AggregateLines.merge(command.lines)
            }
        val replacement =
            old.copy(
                periodStart = command.periodStart,
                periodEnd = command.periodEnd,
                sourceRevision = command.sourceRevision,
                lines = lines,
            )
        return try {
            IntakeOutcome.Replaced(store.replace(current, replacement), current.aggregateId)
        } catch (_: DuplicateIntakeKey) {
            IntakeOutcome.Replayed(winner(command))
        }
    }

    private fun supplement(
        command: IntakeCommand,
        current: StoredAggregate,
    ): IntakeOutcome {
        val store = checkNotNull(revisions)
        val difference =
            AggregateLines.difference(command.lines, store.recordedLines(command.entityRef, command.sourceKey))
        if (difference.isEmpty()) {
            checkNotNull(seen).recordSeen(current, command.sourceRevision)
            return IntakeOutcome.Unchanged(current)
        }
        return when (val made = create(command.copy(lines = difference), supplementary = true)) {
            is IntakeOutcome.Created -> IntakeOutcome.Supplemented(made.aggregate)
            else -> made
        }
    }

    private fun winner(command: IntakeCommand): StoredAggregate =
        repository.findByIntakeKey(command.entityRef, command.sourceKey, command.sourceRevision)
            ?: error("a duplicate intake key without a stored aggregate")

    /**
     * ING-018 (INV-REQ-435): with a source, known spans must hold both dates in one and the same span; a date in no
     * span fails closed. Unknown spans are accepted (no crossing can be judged; the window is held, never split on a
     * guess). Without a source nothing is checked.
     */
    private fun crossesBoundary(command: IntakeCommand): Boolean {
        val known = periods?.spans(command.entityRef, command.periodStart, command.periodEnd)
        return known is PeriodSpans.Known &&
            known.spans.none { command.periodStart in it.start..it.end && command.periodEnd in it.start..it.end }
    }

    /** Steps 1 and 2: a replay of the triple, or a revision lower than a recorded one; null when neither. */
    private fun alreadyKnown(command: IntakeCommand): IntakeOutcome? {
        val stored = repository.findByIntakeKey(command.entityRef, command.sourceKey, command.sourceRevision)
        if (stored != null) return IntakeOutcome.Replayed(stored)
        val highest = repository.highestRevision(command.entityRef, command.sourceKey)
        return if (highest != null && highest > command.sourceRevision) {
            IntakeOutcome.SourceRevisionLower(highest)
        } else {
            null
        }
    }

    /** Step 3: both codes are read, the entity code is judged first. */
    private fun create(
        command: IntakeCommand,
        supplementary: Boolean = false,
    ): IntakeOutcome {
        val entityCode = validCode(shortCodes.entityCode(command.entityRef))
        val businessUnitCode = validCode(shortCodes.businessUnitCode(command.businessUnitRef))
        return when {
            entityCode == null -> IntakeOutcome.NumberNotBuildable(NotBuildableReason.ENTITY_CODE)
            businessUnitCode == null -> IntakeOutcome.NumberNotBuildable(NotBuildableReason.BUSINESS_UNIT_CODE)
            else -> number(command, entityCode, businessUnitCode, supplementary)
        }
    }

    /** Steps 4 and 5: sequence, number, store; a lost race answers with the aggregate that won. */
    private fun number(
        command: IntakeCommand,
        entityCode: String,
        businessUnitCode: String,
        supplementary: Boolean,
    ): IntakeOutcome {
        val sequence = repository.nextSequence()
        val number =
            AggregateNumber.of(entityCode, command.aggregateType, businessUnitCode, command.periodEnd, sequence)
        // Codes of at most 10 characters and a Long sequence cannot exceed 64 characters.
        check(number is NumberingResult.Numbered) { "a number built from valid codes fits documentNumber" }
        val issueDate = AggregateNumber.issueDate(command.periodEnd)
        val placement = place(command)
        val new =
            NewAggregate(
                entityRef = command.entityRef,
                businessUnitRef = command.businessUnitRef,
                aggregateType = command.aggregateType,
                periodStart = command.periodStart,
                periodEnd = command.periodEnd,
                sourceKey = command.sourceKey,
                sourceRevision = command.sourceRevision,
                sequence = sequence,
                entityCode = entityCode,
                businessUnitCode = businessUnitCode,
                documentNumber = number.number,
                issueDate = issueDate,
                transactionDate = issueDate,
                windowStart = placement?.window?.start,
                windowEnd = placement?.window?.end,
                windowState = placement?.state,
                lines = if (supplementary) command.lines else AggregateLines.merge(command.lines),
                isSupplementary = supplementary,
            )
        return try {
            IntakeOutcome.Created(repository.store(new))
        } catch (_: DuplicateIntakeKey) {
            IntakeOutcome.Replayed(winner(command))
        }
    }

    /** With a window repository the aggregate is placed in the window of its `periodEnd`; without one, not at all. */
    private fun place(command: IntakeCommand): Placement? {
        val repo = windows ?: return null
        val source = checkNotNull(cadences)
        val cadence = source.cadence(command.entityRef, command.aggregateType, command.periodEnd)
        return repo.place(command.entityRef, command.businessUnitRef, command.aggregateType, command.periodEnd, cadence)
    }

    private fun validCode(code: String?): String? = code?.takeIf { CODE.matches(it) }

    private companion object {
        val CODE = Regex("^[A-Z0-9]{1,10}$")
    }
}
