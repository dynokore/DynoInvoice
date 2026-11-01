package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.PeriodSplit
import com.dynokore.gst.aggregation.domain.SplitResult
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import java.time.LocalDate
import java.util.UUID

/** What one closing run did: the parts it closed and the windows it held. */
data class CloseResult(
    val closed: List<WindowChange>,
    val held: List<OpenWindow>,
)

/**
 * The closing run of a tick (INV-REQ-413 item 3, INV-REQ-435 items 1 and 2). Each open window whose end is on or before
 * the tick is split at the prescribed periods; one aggregate is made per part, with the part dates as its period.
 * The source key `window:<entityRef>:<aggregateType>:<partStart>:<partEnd>` at revision 1 makes a second firing of the
 * same tick replay: nothing is created, closed or published twice. A window with no known span, or with a date no span
 * covers, stays open (held).
 *
 * [intake] must be an [IntakeAggregate] built without a window repository: the part is closed here, not placed again.
 */
class CloseWindows(
    private val windows: WindowRepository,
    private val periods: PeriodBoundarySource,
    private val intake: IntakeAggregate,
    private val publisher: WindowChangePublisher,
) {
    fun execute(tick: LocalDate): CloseResult {
        val closed = mutableListOf<WindowChange>()
        val held = mutableListOf<OpenWindow>()
        for (open in windows.openWindowsEndingOnOrBefore(tick)) {
            val parts = partsOf(open)
            if (parts == null) {
                held += open
                continue
            }
            var complete = true
            for (part in parts) {
                val command =
                    IntakeCommand(
                        entityRef = open.entityRef,
                        businessUnitRef = open.businessUnitRef,
                        aggregateType = open.aggregateType,
                        periodStart = part.start,
                        periodEnd = part.end,
                        sourceKey = "window:${open.entityRef}:${open.aggregateType}:${part.start}:${part.end}",
                        sourceRevision = 1,
                    )
                val outcome = intake.execute(command)
                if (outcome is IntakeOutcome.Created) {
                    val change = close(open, part, outcome.aggregate.aggregateId)
                    closed += change
                } else if (outcome !is IntakeOutcome.Replayed) {
                    // a replay was made by an earlier firing and is not closed or published again; anything else holds
                    complete = false
                }
            }
            if (!complete) held += open
        }
        return CloseResult(closed, held)
    }

    private fun close(
        open: OpenWindow,
        part: WindowPart,
        aggregateId: UUID,
    ): WindowChange {
        windows.closePart(open, part, aggregateId)
        return WindowChange(open, part, WindowState.CLOSED, aggregateId).also(publisher::changed)
    }

    private fun partsOf(open: OpenWindow) =
        when (val known = periods.spans(open.entityRef, open.window.start, open.window.end)) {
            PeriodSpans.Unknown -> {
                null
            }

            is PeriodSpans.Known -> {
                when (val split = PeriodSplit.split(open.window, known.spans)) {
                    is SplitResult.Split -> split.parts
                    is SplitResult.Uncovered -> null
                }
            }
        }
}
