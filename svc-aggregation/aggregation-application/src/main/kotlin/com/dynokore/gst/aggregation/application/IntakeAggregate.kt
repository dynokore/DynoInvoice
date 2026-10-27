package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateNumber
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.NumberingResult
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
)

data class StoredAggregate(
    val aggregate: NewAggregate,
    val aggregateId: UUID,
)

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

    data class NumberNotBuildable(
        val reason: NotBuildableReason,
    ) : IntakeOutcome
}

/**
 * Idempotent intake of one aggregate (INV-REQ-427, INV-REQ-434 items 1, 2, 3, 5). Replay is matched on the triple
 * (entityRef, sourceKey, sourceRevision); a replay returns the stored aggregate and reads neither the short codes nor
 * the sequence, so the number never changes (INV-REQ-436 item 3).
 */
class IntakeAggregate(
    private val shortCodes: ShortCodes,
    private val repository: AggregateRepository,
) {
    fun execute(command: IntakeCommand): IntakeOutcome = alreadyKnown(command) ?: create(command)

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
    private fun create(command: IntakeCommand): IntakeOutcome {
        val entityCode = validCode(shortCodes.entityCode(command.entityRef))
        val businessUnitCode = validCode(shortCodes.businessUnitCode(command.businessUnitRef))
        return when {
            entityCode == null -> IntakeOutcome.NumberNotBuildable(NotBuildableReason.ENTITY_CODE)
            businessUnitCode == null -> IntakeOutcome.NumberNotBuildable(NotBuildableReason.BUSINESS_UNIT_CODE)
            else -> number(command, entityCode, businessUnitCode)
        }
    }

    /** Steps 4 and 5: sequence, number, store; a lost race answers with the aggregate that won. */
    private fun number(
        command: IntakeCommand,
        entityCode: String,
        businessUnitCode: String,
    ): IntakeOutcome {
        val sequence = repository.nextSequence()
        val number =
            AggregateNumber.of(entityCode, command.aggregateType, businessUnitCode, command.periodEnd, sequence)
        // Codes of at most 10 characters and a Long sequence cannot exceed 64 characters.
        check(number is NumberingResult.Numbered) { "a number built from valid codes fits documentNumber" }
        val issueDate = AggregateNumber.issueDate(command.periodEnd)
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
            )
        return try {
            IntakeOutcome.Created(repository.store(new))
        } catch (_: DuplicateIntakeKey) {
            val winner =
                repository.findByIntakeKey(command.entityRef, command.sourceKey, command.sourceRevision)
                    ?: error("a duplicate intake key without a stored aggregate")
            IntakeOutcome.Replayed(winner)
        }
    }

    private fun validCode(code: String?): String? = code?.takeIf { CODE.matches(it) }

    private companion object {
        val CODE = Regex("^[A-Z0-9]{1,10}$")
    }
}
