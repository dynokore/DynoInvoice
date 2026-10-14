package com.dynokore.platform.serviceruntime.eventloop

import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.DoubleSupplier

/** What a loop drives. */
enum class EventLoopKind(
    val label: String,
) {
    RELAY("relay"),
    CONSUMER("consumer"),
    REPLAY("replay"),
}

/**
 * One continuous loop (INV-T-3938): calls [call] on its own virtual thread, in the background lane of the service's
 * pool ([PoolLane.BACKGROUND], INV-REQ-1230 b1), and calls it again at once while the last call [call] returned true
 * (it did work), otherwise after [interval]. A call that throws an Exception is logged (CS-34: class and SQLSTATE
 * only, never the message) and retried after a bounded, jittered backoff; the loop goes on. An Error ends the loop:
 * it is logged, [ended] is set and the service's readiness goes DOWN ([EventLoopHealthIndicator]).
 *
 * [stop] lets the call in hand finish; it interrupts only after [stop]'s timeout.
 *
 * The loop binds no tenant: the relay, consumers and replay delivery bind the service's workload principal and each
 * row's tenant in their own transactions (INV-REQ-854 b1).
 */
class EventLoop(
    val name: String,
    val kind: EventLoopKind,
    private val interval: Duration,
    private val clock: Clock,
    private val jitter: DoubleSupplier,
    private val onEnded: (EventLoop) -> Unit,
    private val call: () -> Boolean,
) {
    private val stopRequested = CountDownLatch(1)
    private val last = AtomicReference<Instant?>(null)
    private val endedWith = AtomicReference<Throwable?>(null)

    @Volatile
    private var thread: Thread? = null

    /** The time of the last call that returned normally; null before the first. */
    fun lastSuccess(): Instant? = last.get()

    /** The Error that ended the loop; null while it runs or after a normal stop. */
    fun ended(): Throwable? = endedWith.get()

    fun isAlive(): Boolean = thread?.isAlive == true

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread.ofVirtual().name("event-loop-${kind.label}-$name").start { run() }
    }

    /** Asks the loop to stop after the call in hand. */
    fun requestStop() = stopRequested.countDown()

    /** Waits up to [timeout] for the loop to end, and interrupts it when it has not; true when it ended in time. */
    fun awaitStop(timeout: Duration): Boolean {
        val running = thread ?: return true
        running.join(timeout)
        if (!running.isAlive) return true
        log.warn("event loop did not stop within the timeout, interrupting: loop={} kind={}", name, kind.label)
        running.interrupt()
        running.join(timeout)
        return !running.isAlive
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run() {
        var failures = 0
        while (stopRequested.count > 0) {
            var wait: Duration
            try {
                val worked = PoolLanes.inLane(PoolLane.BACKGROUND, call)
                failures = 0
                last.set(clock.instant())
                wait = if (worked) Duration.ZERO else interval
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (stopRequested.count == 0L) return
                failures++
                wait = backoff(failures)
                log.warn(
                    "event loop call failed, retrying: loop={} kind={} error_class={} sql_state={} retry_in_ms={}",
                    name,
                    kind.label,
                    error.javaClass.name,
                    sqlState(error),
                    wait.toMillis(),
                )
            } catch (error: Throwable) {
                endedWith.set(error)
                log.error(
                    "event loop ended, the service is not ready: loop={} kind={} error_class={}",
                    name,
                    kind.label,
                    error.javaClass.name,
                )
                onEnded(this)
                return
            }
            if (!wait.isZero && stopRequested.await(wait.toNanos(), TimeUnit.NANOSECONDS)) return
        }
    }

    /**
     * [interval] doubled per consecutive failure, capped at [MAX_BACKOFF] (or [interval] when longer), jittered down
     * to half.
     */
    fun backoff(failures: Int): Duration {
        val cap = maxOf(MAX_BACKOFF, interval)
        val shift = (failures - 1).coerceIn(0, MAX_SHIFT)
        val raw = minOf(cap.toMillis(), interval.toMillis() shl shift)
        val spread = 1.0 - jitter.asDouble * JITTER_FRACTION
        return Duration.ofMillis(maxOf(1L, (raw * spread).toLong()))
    }

    private fun sqlState(error: Throwable): String? {
        var cause: Throwable? = error
        repeat(CAUSE_DEPTH) {
            (cause as? SQLException)?.let { return it.sqlState }
            cause = cause?.cause
        }
        return null
    }

    companion object {
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)
        private const val MAX_SHIFT = 20
        private const val JITTER_FRACTION = 0.5
        private const val CAUSE_DEPTH = 8
        private val log = LoggerFactory.getLogger(EventLoop::class.java)
    }
}
