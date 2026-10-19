package com.dynokore.platform.productruntime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the housekeeping passes a named HikariCP pool completes while the probe is open (INV-T-5668). A test-only
 * hook built from what test sources reach: HikariCP's housekeeper logs "<pool> - Before cleanup stats (...)" at DEBUG
 * once per pass, and the probe counts those events on the pool's logger. It reads no pool setting, writes none, and
 * touches no production code; only the level of the HikariPool logger is raised to DEBUG while open (restored on
 * close).
 */
class HousekeepingPassProbe(
    poolName: String,
) : AutoCloseable {
    private val passPrefix = "$poolName - $PASS_MARKER"
    private val passes = AtomicInteger()
    private val logger = LoggerFactory.getLogger(HIKARI_POOL_LOGGER) as Logger
    private val previousLevel: Level? = logger.level
    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (event.formattedMessage.startsWith(passPrefix)) passes.incrementAndGet()
            }
        }

    init {
        appender.start()
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    /** The passes completed so far since this probe was opened. */
    fun passes(): Int = passes.get()

    /** Polls [passes] until it reaches [target] or [deadlineMs] has passed; returns the count last read. */
    fun awaitPasses(
        target: Int,
        deadlineMs: Long,
    ): Int {
        val end = System.nanoTime() + deadlineMs * NANOS_PER_MS
        while (passes() < target && System.nanoTime() < end) Thread.sleep(POLL_MS)
        return passes()
    }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }

    private companion object {
        const val HIKARI_POOL_LOGGER = "com.zaxxer.hikari.pool.HikariPool"
        const val PASS_MARKER = "Before cleanup"
        const val NANOS_PER_MS = 1_000_000L
        const val POLL_MS = 250L
    }
}
