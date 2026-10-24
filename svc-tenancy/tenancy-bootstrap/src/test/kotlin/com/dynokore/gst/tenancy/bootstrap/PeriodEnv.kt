package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.PeriodsController
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.FilingFrequencyCommands
import com.dynokore.gst.tenancy.application.period.FrequencyEntry
import com.dynokore.gst.tenancy.application.period.FrequencyHistoryStore
import com.dynokore.gst.tenancy.application.period.HistoryState
import com.dynokore.gst.tenancy.application.period.PeriodGeneration
import com.dynokore.gst.tenancy.application.period.PeriodRecordStore
import com.dynokore.gst.tenancy.application.period.ProposalStore
import com.dynokore.gst.tenancy.application.period.RaisedCode
import com.dynokore.gst.tenancy.application.period.StoredPeriod
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.contract.model.ConfirmPeriodRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyChangeRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponse
import com.dynokore.gst.tenancy.contract.model.PeriodResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import org.springframework.http.ResponseEntity
import java.math.BigDecimal
import java.util.UUID

internal typealias Freq = FilingFrequencyChangeRequest.NewFrequency
internal typealias Kind = ConfirmPeriodRequest.Kind

/** The period and frequency operations of the real application context, with the reads the tests assert on. */
internal class PeriodEnv(
    val env: ProfileEnv,
) {
    private val context = env.context
    val periodsApi: PeriodsController = context.getBean(PeriodsController::class.java)
    val generation: PeriodGeneration = context.getBean(PeriodGeneration::class.java)
    val commands: FilingFrequencyCommands = context.getBean(FilingFrequencyCommands::class.java)
    val confirmation: ConfirmPeriods = context.getBean(ConfirmPeriods::class.java)
    val calendar: FixtureCalendar by lazy { context.getBean(FixtureCalendar::class.java) }
    val people: TestPeople = context.getBean(TestPeople::class.java)
    private val history: FrequencyHistoryStore = context.getBean(FrequencyHistoryStore::class.java)
    private val proposals: ProposalStore = context.getBean(ProposalStore::class.java)
    private val periods: PeriodRecordStore = context.getBean(PeriodRecordStore::class.java)
    private val structure: StructureStore = context.getBean(StructureStore::class.java)

    /** The caller of the next calls holds exactly [roles] on [entity] (and nothing on any other entity). */
    fun actAs(
        entity: EntityResponse,
        vararg roles: String,
    ) {
        people.caller = PERIOD_CALLER
        people.grant(PERIOD_CALLER, entity.entityRef, *roles)
    }

    /** Records the GST registration start date the way the service stores it: an OBLIGATION_START status row. */
    fun register(
        entity: EntityResponse,
        registration: String,
    ) {
        actAs(entity, "entity.admin")
        env.assess(entity, box4 = BigDecimal("100000"))
        env.set(
            entity,
            SetEntityStatusRequest(
                SetEntityStatusRequest.StatusKind.OBLIGATION_START,
                TODAY,
                registrationStartDate = day(registration),
            ),
        )
    }

    fun change(
        entity: EntityResponse,
        frequency: Freq,
        from: String,
        iras: String = "IRAS-1",
    ): ResponseEntity<FilingFrequencyResponse> =
        env.api.changeFilingFrequency(
            entity.entityRef,
            UUID.randomUUID(),
            FilingFrequencyChangeRequest(frequency, day(from), iras),
        )

    fun confirm(
        entity: EntityResponse,
        periodRef: String,
        start: String,
        end: String,
        kind: Kind,
    ): ResponseEntity<PeriodResponse> =
        periodsApi.confirmPeriods(
            entity.entityRef,
            UUID.randomUUID(),
            ConfirmPeriodRequest(periodRef, day(start), day(end), kind),
        )

    fun entityId(entity: EntityResponse): UUID = env.inTx { structure.entity(entity.entityRef)!!.id }

    fun records(entity: EntityResponse): List<StoredPeriod> = env.inTx { periods.inForce(entityId(entity)) }

    fun entries(entity: EntityResponse): List<FrequencyEntry> =
        env.inTx { history.entries(entityId(entity)) }.filter { it.state != HistoryState.SUPERSEDED }

    /** The raised items of the entity per code that are in the given state. */
    fun items(
        entity: EntityResponse,
        state: com.dynokore.gst.tenancy.application.period.ItemState,
    ): Map<RaisedCode, Int> =
        env
            .inTx { proposals.items(entityId(entity)) }
            .filter { it.state == state }
            .groupingBy { it.code }
            .eachCount()

    fun insertRecord(
        entity: EntityResponse,
        period: StoredPeriod,
    ) = env.inTx { periods.insert(entityId(entity), period) }

    /** Every stored row of the entity's four period tables, as JSON text in a fixed order (every field and state). */
    fun rows(entity: EntityResponse): List<String> {
        val id = entityId(entity)
        return BoundaryFixture.asSuperuser { c ->
            listOf(
                "period_record" to "period_start, recorded_at",
                "filing_frequency_history" to "effective_from, recorded_at",
                "proposed_period" to "history_id, period_start",
                "raised_item" to "history_id, code",
            ).flatMap { (table, order) ->
                val sql = "select row_to_json(t)::text from tenancy.$table t where entity_id = ? order by $order"
                c.prepareStatement(sql).use { s ->
                    s.setObject(1, id)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
            }
        }
    }

    /** The FilingFrequencyChanged envelopes the outbox has appended so far. */
    fun frequencyEvents() = env.captured.envelopes.filter { it.type == FILING_FREQUENCY_CHANGED }

    companion object {
        const val PERIOD_CALLER = "identity:USR-040"
        const val FILING_FREQUENCY_CHANGED = "com.dynokore.gst.tenancy.FilingFrequencyChanged.v1"
    }
}
