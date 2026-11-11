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
 * The closing run of a tick (INV-REQ-413 item 3, INV-REQ-435 items 1 and 2). Each open window that starts on or
 * before the tick is split at the prescribed periods; one aggregate is made per part, with the part dates as its
 * period. Only a part whose end is on or before the tick is due (a part ending on the tick is due) and is closed;
 * a part that ends after the tick gets no aggregate, no part row and no event, and stays open for a later tick.
 * The source key `window:<entityRef>:<aggregateType>:<partStart>:<partEnd>` at revision 1 makes a second firing of the
 * same tick replay: nothing is created twice, and a part left open by a failed run is closed and published by the
 * next. A window with no known span, or with a date no span covers, is held when its own end is on or before the
 * tick; a window that ends after the tick and has no usable spans is neither closed nor held.
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
        for (open in windows.openWindowsStartingOnOrBefore(tick)) {
            val parts = partsOf(open)
            if (parts == null) {
                if (open.window.end <= tick) held += open
                continue
            }
            var complete = true
            for (part in parts.filter { it.end <= tick }) {
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
                val aggregate =
                    when (val outcome = intake.execute(command)) {
                        is IntakeOutcome.Created -> outcome.aggregate

                        // made by an earlier run that may have failed before closing or publishing: finish it below
                        is IntakeOutcome.Replayed -> outcome.aggregate

                        else -> null
                    }
                if (aggregate == null) {
                    complete = false
                } else if (!windows.isPartClosed(open, part)) {
                    closed += close(open, part, aggregate.aggregateId)
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
        // publish first: a failure between the two leaves the part open, so the next run publishes again
        // (at-least-once) rather than never; consumers deduplicate on the aggregate id
        val change = WindowChange(open, part, WindowState.CLOSED, aggregateId)
        publisher.changed(change)
        windows.closePart(open, part, aggregateId)
        return change
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
