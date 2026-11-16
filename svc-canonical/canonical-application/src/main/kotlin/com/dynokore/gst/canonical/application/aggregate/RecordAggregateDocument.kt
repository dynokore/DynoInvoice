package com.dynokore.gst.canonical.application.aggregate

import com.dynokore.gst.canonical.application.document.AppliedPackVersion
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.LineVerdictCombination
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.RecordedResult
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.source.SourceKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** The descriptive item name of every aggregate line (INV-REQ-437 behavior 3). */
const val AGGREGATE_ITEM_NAME = "Aggregate"

/** The unit of every aggregate line (INV-REQ-437 behavior 3). */
const val AGGREGATE_UNIT = "LOT"

/** One line of an AggregateReady aggregate. [taxableAmount] is stated by aggregation-svc for a PCP aggregate only. */
data class AggregateLine(
    val internalTaxCode: String,
    val amount: BigDecimal,
    val taxableAmount: BigDecimal? = null,
)

/** The AggregateReady aggregate as this use case reads it. */
data class AggregateInput(
    val tenantId: UUID,
    val aggregateId: String,
    val entityRef: String,
    val aggregateType: String,
    val periodRef: String?,
    val windowTo: LocalDate?,
    val isCreditNote: Boolean,
    val lines: List<AggregateLine>,
)

/** The answer of the rules classification of one internal tax code. */
sealed interface LineAnswer

/** [packVersions] is keyed by pack type (SCOPE, MAPPING, MDE); [rate] is a percent, null when rules-svc states none. */
data class Classified(
    val categoryCode: String,
    val lineVerdict: String,
    val packVersions: Map<String, String>,
    val rate: BigDecimal?,
) : LineAnswer

/** Rules-svc refused the code or has no mapping for it. */
object Refused : LineAnswer

/** Rules-svc could not answer now. */
object Unavailable : LineAnswer

fun interface LineClassifier {
    fun classify(
        entityRef: String,
        transactionDate: LocalDate,
        internalTaxCode: String,
        direction: Direction,
    ): LineAnswer
}

fun interface AggregateEntityIds {
    fun entityIdOf(
        tenantId: UUID,
        entityRef: String,
    ): UUID?
}

/** One line per distinct category code; [amount] is the merged taxable amount; [rate] is null for a PCP aggregate. */
data class MergedLine(
    val categoryCode: String,
    val amount: BigDecimal,
    val rate: BigDecimal?,
)

data class TaxSubtotal(
    val categoryCode: String,
    val taxableAmount: BigDecimal,
    val taxAmount: BigDecimal,
)

data class AggregateAmounts(
    val subtotals: List<TaxSubtotal>,
    val totalExcludingTax: BigDecimal,
    val totalTax: BigDecimal,
    val totalIncludingTax: BigDecimal,
)

/** Builds the canonical document JSON text; its adapter belongs to the consumer task. */
fun interface AggregateDocumentJson {
    fun build(
        lines: List<MergedLine>,
        amounts: AggregateAmounts,
        isCreditNote: Boolean,
        aggregateType: String,
    ): String
}

/** The aggregate is refused for good: nothing is written and a retry gives the same answer. */
class AggregateRefused(
    val code: String,
    message: String,
) : RuntimeException("$code: $message")

/** The aggregate cannot be recorded yet; the exception propagates so the record rolls back and the event is retried. */
class AggregateRetry(
    message: String,
) : RuntimeException(message)

object AggregateDirection {
    fun of(aggregateType: String): Direction =
        when (aggregateType) {
            "POS", "STI" -> Direction.SALE
            "PCP" -> Direction.PURCHASE
            else -> throw IllegalArgumentException("unknown aggregate type: $aggregateType")
        }
}

/**
 * Records an AggregateReady aggregate as one canonical document (INV-REQ-437, INV-REQ-718). It opens no transaction
 * beyond the one [RecordSourceEvent.record] opens.
 */
class RecordAggregateDocument(
    private val sourceEvents: RecordSourceEvent,
    private val classifier: LineClassifier,
    private val entityIds: AggregateEntityIds,
    private val json: AggregateDocumentJson,
    private val writer: CanonicalVersionWriter,
) {
    fun record(input: AggregateInput): RecordedResult =
        sourceEvents.record(SourceKey(input.tenantId, SOURCE_SYSTEM, input.aggregateId, 1)) { docUuid ->
            write(docUuid, input)
            """{"docUuid":"$docUuid","aggregateId":${quote(input.aggregateId)}}"""
        }

    private class Checked(
        val direction: Direction,
        val windowTo: LocalDate,
        val periodRef: String,
        val entityId: UUID,
    )

    private fun check(input: AggregateInput): Checked {
        val direction = AggregateDirection.of(input.aggregateType)
        if (direction == Direction.PURCHASE && input.lines.any { it.taxableAmount == null }) {
            refuse("PCP_TAXABLE_AMOUNT_MISSING", "a PCP line has no taxable amount")
        }
        if (input.aggregateId.length !in 1..MAX_ID) refuse("BAD_AGGREGATE_ID", "length outside 1..$MAX_ID")
        val windowTo = input.windowTo
        val periodRef = input.periodRef
        if (windowTo == null || periodRef == null) refuse("WINDOW_INCOMPLETE", "window end or period is missing")
        val entityId =
            entityIds.entityIdOf(input.tenantId, input.entityRef)
                ?: throw AggregateRetry("entity ${input.entityRef} has no id yet")
        return Checked(direction, windowTo, periodRef, entityId)
    }

    /** What the rules answers and the merge give: the inputs of the document and its event facts. */
    private class Derived(
        val verdict: ScopeVerdict,
        val packs: Map<String, String>,
        val canonical: String,
    )

    private fun write(
        docUuid: UUID,
        input: AggregateInput,
    ) {
        val c = check(input)
        val purchase = c.direction == Direction.PURCHASE
        val classified =
            input.lines.map { line ->
                when (val a = classifier.classify(input.entityRef, c.windowTo, line.internalTaxCode, c.direction)) {
                    is Classified -> line to a
                    Refused -> refuse("UNMAPPED_TAX_CODE", "internal tax code ${line.internalTaxCode} is not mapped")
                    Unavailable -> throw AggregateRetry("rules answer unavailable")
                }
            }
        val packs = packVersions(classified.map { it.second })
        val merged = merge(classified, purchase)
        val verdict = LineVerdictCombination.combine(classified.map { it.second.lineVerdict })
        val lines = merged.map { MergedLine(it.categoryCode, it.taxable, it.rate) }
        val canonical = json.build(lines, amounts(merged, purchase), input.isCreditNote, input.aggregateType)
        val derived = Derived(ScopeVerdict.valueOf(verdict), packs, canonical)
        writer.createFirst(document(docUuid, input, c, derived), facts(docUuid, input, c, derived))
    }

    private fun document(
        docUuid: UUID,
        input: AggregateInput,
        c: Checked,
        d: Derived,
    ) = NewDocument(
        docUuid = docUuid,
        tenantId = input.tenantId,
        entityId = c.entityId,
        businessUnitId = null,
        direction = c.direction,
        documentTypeCode = if (input.isCreditNote) CREDIT_NOTE else INVOICE,
        documentNumber = input.aggregateId,
        issuerPartyKey = input.entityRef,
        fiscalYear = c.windowTo.year.toShort(),
        internalReference = null,
        state = if (d.verdict == ScopeVerdict.IN_SCOPE) DocumentState.READY else DocumentState.NOT_SUBMITTABLE,
        prescribedPeriodId = null,
        first =
            NewVersion(
                canonical = d.canonical,
                scopeVerdict = d.verdict,
                scopePackVersion = d.packs.getValue(SCOPE),
                mappingPackVersion = d.packs.getValue(MAPPING),
                mdePackVersion = d.packs[MDE],
                specificationVersion = null,
                transactionDate = c.windowTo,
                createdBy = CREATED_BY,
            ),
    )

    private fun facts(
        docUuid: UUID,
        input: AggregateInput,
        c: Checked,
        d: Derived,
    ) = CanonicalVersionCreatedFacts(
        docUuid = docUuid,
        entityRef = input.entityRef,
        version = 1,
        transactionDate = c.windowTo,
        prescribedPeriodRef = c.periodRef,
        scopeVerdict = d.verdict.name,
        appliedPackVersions =
            listOf(SCOPE, MAPPING, MDE)
                .filter { it in d.packs }
                .map { AppliedPackVersion(it, d.packs.getValue(it)) },
        traceReference = "$docUuid:1",
        canonicalDocument = d.canonical,
        aggregateId = input.aggregateId,
        routingFacts =
            RoutingFactsData(
                direction = RoutingDirection.valueOf(c.direction.name),
                networkPath = RoutingNetworkPath.NOT_ON_NETWORK,
            ),
    )

    private class Group(
        val categoryCode: String,
        var rate: BigDecimal?,
        var amount: BigDecimal,
        var taxable: BigDecimal,
    )

    private fun merge(
        classified: List<Pair<AggregateLine, Classified>>,
        purchase: Boolean,
    ): List<Group> {
        val groups = linkedMapOf<String, Group>()
        for ((line, answer) in classified) {
            val rate =
                if (purchase) {
                    null
                } else {
                    answer.rate
                        ?: refuse("RATE_MISSING", "no rate for ${answer.categoryCode}")
                }
            val taxable = if (purchase) checkNotNull(line.taxableAmount) else line.amount
            val group = groups[answer.categoryCode]
            if (group == null) {
                groups[answer.categoryCode] = Group(answer.categoryCode, rate, line.amount, taxable)
            } else {
                if (rate != null && group.rate?.compareTo(rate) != 0) {
                    refuse("RATE_MISMATCH", "category code ${answer.categoryCode} has different rates")
                }
                group.amount = group.amount.add(line.amount)
                group.taxable = group.taxable.add(taxable)
            }
        }
        groups.values.forEach {
            if (scaleOver2(it.amount) || scaleOver2(it.taxable)) {
                refuse("AMOUNT_SCALE", "amount of ${it.categoryCode} has more than two decimals")
            }
        }
        return groups.values.toList()
    }

    private fun amounts(
        merged: List<Group>,
        purchase: Boolean,
    ): AggregateAmounts {
        val subtotals =
            merged.map {
                val tax =
                    if (purchase) {
                        it.amount
                    } else {
                        it.taxable
                            .multiply(checkNotNull(it.rate))
                            .divide(HUNDRED)
                            .setScale(2, RoundingMode.HALF_UP)
                    }
                TaxSubtotal(it.categoryCode, it.taxable, tax)
            }
        val excl = subtotals.fold(BigDecimal.ZERO) { a, s -> a.add(s.taxableAmount) }
        val tax = subtotals.fold(BigDecimal.ZERO) { a, s -> a.add(s.taxAmount) }
        return AggregateAmounts(subtotals, excl, tax, excl.add(tax))
    }

    private fun packVersions(answers: List<Classified>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (type in listOf(SCOPE, MAPPING)) {
            val version = answers.map { it.packVersions[type] }.toSet().singleOrNull()
            result[type] = version ?: refuse("PACK_VERSION_MISMATCH", "pack $type differs across lines or is absent")
        }
        answers
            .map { it.packVersions[MDE] }
            .toSet()
            .singleOrNull()
            ?.let { result[MDE] = it }
        return result
    }

    private fun scaleOver2(value: BigDecimal) = value.stripTrailingZeros().scale() > 2

    private fun refuse(
        code: String,
        message: String,
    ): Nothing = throw AggregateRefused(code, message)

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val SOURCE_SYSTEM = "aggregation-svc"
        const val CREATED_BY = "system:aggregate-ready"
        const val SCOPE = "SCOPE"
        const val MAPPING = "MAPPING"
        const val MDE = "MDE"
        const val MAX_ID = 100
        const val INVOICE: Short = 380
        const val CREDIT_NOTE: Short = 381
        val HUNDRED = BigDecimal(100)
    }
}
