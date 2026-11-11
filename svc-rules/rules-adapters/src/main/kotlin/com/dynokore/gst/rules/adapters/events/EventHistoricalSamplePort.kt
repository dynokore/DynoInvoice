package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.JooqSamplePageStore
import com.dynokore.gst.rules.application.DryRunWindow
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.SamplePage
import java.time.Clock
import java.time.Duration

/**
 * The production [HistoricalSamplePort] (coordinator ruling INV-Q-4995): the pages are those the inbox consumer of
 * `PackDryRunSampleRecorded` stored in [JooqSamplePageStore]; this class only reads them, waits for them and frees
 * them.
 * It derives, corrects and defaults nothing. The request itself is published by [DryRunSampleEvents] in the command
 * transaction of `dryRun`, so that the request and the run commit or roll back together.
 */
class EventHistoricalSamplePort(
    private val store: JooqSamplePageStore,
    private val clock: Clock,
    private val pause: (Duration) -> Unit = { Thread.sleep(it) },
) : HistoricalSamplePort {
    /**
     * The pages stored for [dryRunId], in page order, once the sample is complete or [wait] has passed. The sample is
     * complete when the page flagged last has arrived and every page number from 1 up to it is present.
     */
    override fun pages(
        dryRunId: String,
        window: DryRunWindow,
        wait: Duration,
    ): List<SamplePage> {
        val deadline = clock.instant().plus(wait)
        var pages = store.pages(dryRunId)
        while (!complete(pages) && clock.instant().isBefore(deadline)) {
            pause(minOf(POLL, Duration.between(clock.instant(), deadline)))
            pages = store.pages(dryRunId)
        }
        return pages
    }

    override fun release(dryRunId: String) = store.release(dryRunId)

    private fun complete(pages: List<SamplePage>): Boolean {
        val last = pages.firstOrNull { it.lastPage }?.number ?: return false
        return pages.map { it.number }.toSet().containsAll((1..last).toList())
    }

    private companion object {
        val POLL: Duration = Duration.ofMillis(100)
    }
}
