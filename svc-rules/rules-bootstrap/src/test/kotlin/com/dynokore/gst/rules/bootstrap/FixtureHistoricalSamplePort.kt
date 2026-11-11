package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.DryRunWindow
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.SamplePage
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Canned sample pages of the test composition (owner ruling INV-Q-4870 B): the documents of each period in
 * [FixtureLastCompletePeriodPort.PERIODS], served for the last `periodCount` periods up to the window's last period. A
 * window that asks for more periods than the fixture holds gets pages without a last page, which is an incomplete
 * sample. It exists in the test source set only.
 */
class FixtureHistoricalSamplePort : HistoricalSamplePort {
    val windows = CopyOnWriteArrayList<DryRunWindow>()
    val released = CopyOnWriteArrayList<String>()

    /** The `wait` each read was given (the property `rules.dry-run.sample-wait` reaches the port). */
    val waits = CopyOnWriteArrayList<Duration>()

    /** While set, every read holds its background slot until the latch opens (the saturation cases). */
    @Volatile var hold: CountDownLatch? = null
    private val byPeriod = ConcurrentHashMap<String, List<Map<String, Any?>>>()

    /** Makes the read take this long, so that a run outlasts the 2,000 ms of the synchronous answer. */
    @Volatile var delayMillis: Long = 0

    /** Serves exactly these pages in place of the period pages (a page missing before the last one). */
    @Volatile var pagesOverride: List<SamplePage>? = null

    /** Replaces the documents of [period]; every document is a raw page item as canonical-svc publishes it. */
    fun serve(
        period: String,
        documents: List<Map<String, Any?>>,
    ) {
        byPeriod[period] = documents
    }

    fun clear() {
        byPeriod.clear()
        delayMillis = 0
        pagesOverride = null
        windows.clear()
        released.clear()
        waits.clear()
        hold?.countDown()
        hold = null
    }

    override fun pages(
        dryRunId: String,
        window: DryRunWindow,
        wait: Duration,
    ): List<SamplePage> {
        windows.add(window)
        waits.add(wait)
        hold?.await()
        if (delayMillis > 0) Thread.sleep(delayMillis)
        pagesOverride?.let { return it }
        val periods = FixtureLastCompletePeriodPort.PERIODS
        val end = periods.indexOf(window.lastPeriodRef)
        val served = if (end < 0) emptyList() else periods.subList(maxOf(0, end + 1 - window.periodCount), end + 1)
        val complete = served.size == window.periodCount
        val documents = served.flatMap { byPeriod[it].orEmpty() }
        return if (complete) {
            listOf(SamplePage(1, true, documents))
        } else {
            // the last page never arrives: the run ends SAMPLE_UNAVAILABLE naming the missing page
            listOf(SamplePage(1, false, documents))
        }
    }

    override fun release(dryRunId: String) {
        released.add(dryRunId)
    }
}
