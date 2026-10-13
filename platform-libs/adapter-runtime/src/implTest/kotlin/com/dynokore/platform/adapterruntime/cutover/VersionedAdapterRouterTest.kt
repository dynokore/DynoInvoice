package com.dynokore.platform.adapterruntime.cutover

import com.dynokore.platform.adapterruntime.AdapterState
import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.TimeBasedCutoverRule
import com.dynokore.platform.adapterruntime.fixtures.FakeManagedAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VersionedAdapterRouterTest {
    private val v1 = AdapterVersion("1.0.0")
    private val v2 = AdapterVersion("2.0.0")
    private val cutoverAt = Instant.parse("2026-10-01T00:00:00Z")

    private fun router(
        clock: Clock,
        old: FakeManagedAdapter = FakeManagedAdapter(v1),
        new: FakeManagedAdapter = FakeManagedAdapter(v2),
    ) = Triple(VersionedAdapterRouter(listOf(old, new), TimeBasedCutoverRule(v1, v2, cutoverAt), clock), old, new)

    @Test
    fun `every registered adapter is started`() {
        val (_, old, new) = router(Clock.fixed(cutoverAt.minusSeconds(1), ZoneOffset.UTC))
        old.state shouldBe AdapterState.RUNNING
        new.state shouldBe AdapterState.RUNNING
    }

    @Test
    fun `current routes to the old version before cutover, the new version at and after it`() {
        val (before, oldBefore, _) = router(Clock.fixed(cutoverAt.minusSeconds(1), ZoneOffset.UTC))
        before.current() shouldBe oldBefore

        val (after, _, newAfter) = router(Clock.fixed(cutoverAt, ZoneOffset.UTC))
        after.current() shouldBe newAfter
    }

    @Test
    fun `retireInactive drains the version the rule no longer names`() {
        val (router, old, _) = router(Clock.fixed(cutoverAt, ZoneOffset.UTC))
        old.beginWork()

        val executor = Executors.newSingleThreadExecutor()
        try {
            val retireFinished = executor.submit { router.retireInactive(Instant.now().plus(5, ChronoUnit.SECONDS)) }
            Thread.sleep(50) // give retireInactive time to start waiting on the in-flight call
            old.state shouldBe AdapterState.DRAINING // in-flight work still holds it open

            old.finishWork()
            retireFinished.get(5, TimeUnit.SECONDS)
            old.state shouldBe AdapterState.STOPPED
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `retireInactive never touches the currently active version`() {
        val (router, _, new) = router(Clock.fixed(cutoverAt, ZoneOffset.UTC))
        router.retireInactive(Instant.now().plus(5, ChronoUnit.SECONDS))
        new.state shouldBe AdapterState.RUNNING
    }

    @Test
    fun `rejects more than one adapter registered for the same version`() {
        val duplicate = FakeManagedAdapter(v1)
        val alsoDuplicate = FakeManagedAdapter(v1)
        shouldThrow<IllegalArgumentException> {
            VersionedAdapterRouter(
                listOf(duplicate, alsoDuplicate),
                TimeBasedCutoverRule(v1, v2, cutoverAt),
                Clock.fixed(cutoverAt, ZoneOffset.UTC),
            )
        }
    }
}
