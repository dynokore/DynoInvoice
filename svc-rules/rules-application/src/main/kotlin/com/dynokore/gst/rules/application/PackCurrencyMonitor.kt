package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.PackCurrency
import com.dynokore.gst.rules.domain.pack.PackCurrencyState
import com.dynokore.gst.rules.domain.pack.SilenceLimit
import com.dynokore.gst.rules.domain.pack.UnactivatedPack
import java.time.Clock
import java.time.LocalDate

/** What the installation holds of DEP-001: the day the feed was last reached or a bundle last imported, and the tuple. */
data class StoredCurrency(
    val lastReachedOn: LocalDate,
    /** The last published tuple of the condition; null when nothing was published yet. */
    val published: PackCurrencyState?,
    val evaluatedOn: LocalDate?,
)

/** The state of DEP-001 and the one transaction that records an evaluation and publishes `PackCurrencyChanged`. */
interface PackCurrencyStore {
    fun load(): StoredCurrency?

    /** The earliest published regulatory pack that is staged, approved or acknowledged but not armed, else null. */
    fun unactivated(): UnactivatedPack?

    /** Records [day] as the last day the feed was reached or a bundle imported (creates the state when absent). */
    fun markReached(day: LocalDate)

    /** Records the evaluation of [day]; when [publish] is true the tuple becomes the published one and the event is queued. */
    fun record(
        day: LocalDate,
        state: PackCurrencyState,
        publish: Boolean,
    )
}

/** The silence limit in force (VP-72), read from the policy replica at installation scope of the platform tenant. */
fun interface SilenceLimitPort {
    fun limit(): SilenceLimit
}

/**
 * DEP-001 (INV-REQ-1080, INV-REQ-2468): evaluated once per calendar day of the injected clock and at each import or
 * activation. `PackCurrencyChanged` is published only when the tuple differs from the last published one.
 */
class PackCurrencyMonitor(
    private val store: PackCurrencyStore,
    private val limit: SilenceLimitPort,
    private val clock: Clock,
) {
    /** The daily evaluation: does nothing when today was already evaluated. */
    fun tick() {
        val today = today()
        if (store.load()?.evaluatedOn == today) return
        evaluate(today)
    }

    /** A bundle was imported: the silence clock restarts and the condition is evaluated now. */
    fun bundleImported() {
        val today = today()
        store.markReached(today)
        evaluate(today)
    }

    /** The control-plane feed answered: the silence clock restarts. */
    fun feedReached() {
        store.markReached(today())
    }

    /** A pack was activated: the condition is evaluated now (it clears when no newer pack is left). */
    fun packActivated() = evaluate(today())

    private fun evaluate(today: LocalDate) {
        val stored = store.load() ?: StoredCurrency(today, null, null).also { store.markReached(today) }
        val next = PackCurrency.evaluate(stored.lastReachedOn, today, limit.limit(), store.unactivated())
        store.record(today, next, PackCurrency.mustPublish(stored.published, next))
    }

    private fun today(): LocalDate = LocalDate.now(clock)
}

/** The control-plane feed as the poller sees it: reachable or not; the answer carries nothing the data plane needs. */
fun interface PackFeedProbe {
    /** True when the feed answered; false for every failure (connection refused, timeout, error status). */
    fun reachable(): Boolean
}

/**
 * Polls the feed on the injected clock (INV-REQ-1075): a poll that fails writes nothing and the data plane carries on
 * with the active pack; a poll that succeeds restarts the silence clock.
 */
class PackFeedPoller(
    private val probe: PackFeedProbe,
    private val monitor: PackCurrencyMonitor,
    private val clock: Clock,
    private val interval: java.time.Duration,
) {
    private var nextDue: java.time.Instant? = null

    /** Polls when the interval since the last poll has passed; never throws. Returns whether a poll ran. */
    @Synchronized
    fun tick(): Boolean {
        val now = clock.instant()
        val due = nextDue
        if (due != null && now.isBefore(due)) return false
        nextDue = now.plus(interval)
        if (runCatching { probe.reachable() }.getOrDefault(false)) monitor.feedReached()
        return true
    }
}
