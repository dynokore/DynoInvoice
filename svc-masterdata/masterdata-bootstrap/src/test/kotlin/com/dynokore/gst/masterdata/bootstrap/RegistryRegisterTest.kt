package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.registry.REGISTER_UNAVAILABLE
import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import com.dynokore.platform.adapterruntime.resilience4j.Resilience4jResilientExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.Locale

/**
 * INV-T-4946 at the service boundary, register half: the real context over real PostgreSQL, bound to the
 * certification-kit Check GST Register simulator. The clock moves, so "09:01 the next day" is an age (INV-REQ-477,
 * INV-REQ-801, INV-REQ-802, INV-REQ-1548).
 */
class RegistryRegisterTest {
    private val register = Simulators.register
    private val directory = Simulators.directory

    @Test
    fun `INV-AC-2314 a register result older than 24 hours is looked up again`() =
        runRegistry { env ->
            val entity = env.entity()
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo("REGISTERED")
            val cached = register.calls()
            env.advance(Duration.ofHours(23).plusMinutes(59))
            env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached)
            env.advance(Duration.ofMinutes(2))
            env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached + 1)
        }

    @Test
    fun `INV-AC-2389 a not-found register result is looked up again after 1 hour`() =
        runRegistry { env ->
            val entity = env.entity()
            assertThat(env.lookup(entity, Simulators.UEN_NOT_REGISTERED).status).isEqualTo("NOT_REGISTERED")
            val cached = register.calls()
            env.advance(Duration.ofMinutes(59))
            env.lookup(entity, Simulators.UEN_NOT_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached)
            env.advance(Duration.ofMinutes(2))
            env.lookup(entity, Simulators.UEN_NOT_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached + 1)
        }

    @Test
    fun `INV-AC-1855 INV-AC-342 both registries down gives off-network, REGISTER_UNAVAILABLE only`() =
        runRegistry { env ->
            val entity = env.entity()
            directory.outage(true)
            register.outage(true)
            val fact = env.reach(entity, Simulators.PEPPOL_FOUND)
            val answer = env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(fact.onNetwork).isFalse()
            assertThat(fact.degradationCode).isEqualTo("MDM-008")
            assertThat(answer.status).isEqualTo(REGISTER_UNAVAILABLE)
            assertThat(env.records("DIRECTORY").map { it.degradation }).containsExactly("MDM-008")
        }

    @Test
    fun `INV-AC-1855 an unavailable register is never an error to the call and a later lookup recovers`() =
        runRegistry { env ->
            val entity = env.entity()
            register.outage(true)
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo(REGISTER_UNAVAILABLE)
            register.outage(false)
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo("REGISTERED")
        }

    @Test
    fun `INV-AC-2323 INV-AC-26453 unreachable register retried with bounded jitter then breaker opens`() {
        RegistryStub().use { stub ->
            runRegistry(
                registerUrl = stub.baseUrl,
                customize = { it.withPropertyValues("masterdata.registry.breaker-wait=PT2S") },
            ) { env ->
                val entity = env.entity()
                warmCache(env, stub, entity)
                val afterCache = stub.gets()
                stub.down(true)
                val breakerWait = env.context.getBean(RegistryProperties::class.java).breakerWait
                assertThat(breakerWait).isEqualTo(Duration.ofSeconds(2))
                var n = 0
                val next = { "2019%05dZ".format(Locale.ROOT, n++) }

                val opened = driveUntilOpen(env, stub, entity, next, afterCache)
                val times = stub.times().drop(afterCache)
                assertRetryGaps(times)
                val lastCall = times.last()

                repeat(OPEN_LOOKUPS) {
                    assertThat(env.lookup(entity, next()).status).isEqualTo(REGISTER_UNAVAILABLE)
                    assertThat(stub.gets()).isEqualTo(opened)
                }
                assertThat(env.lookup(entity, CACHED_UEN).status).isEqualTo("REGISTERED")
                assertThat(stub.gets()).isEqualTo(opened)
                assertThat(Duration.ofNanos(System.nanoTime() - lastCall)).isLessThan(breakerWait)

                val waitMs =
                    breakerWait.plusSeconds(1).toMillis() - (System.nanoTime() - lastCall) / NANOS_PER_MS.toLong()
                if (waitMs > 0) Thread.sleep(waitMs)
                env.lookup(entity, next())
                assertThat(stub.gets()).isGreaterThan(opened)
            }
        }
    }

    /** One cached answer before the outage (the probe): one successful breaker call, counted in the window. */
    private fun warmCache(
        env: RegistryEnv,
        stub: RegistryStub,
        entity: String,
    ) {
        stub.found(CACHED_UEN, "{\"uen\":\"$CACHED_UEN\",\"status\":\"REGISTERED\"}")
        assertThat(env.lookup(entity, CACHED_UEN).status).isEqualTo("REGISTERED")
    }

    /**
     * Looks up until the register count stops rising; returns that count. The breaker opens on its
     * BREAKER_WINDOW-th call, the warm-up being one of them: the failing calls before it split into lookups of
     * RETRY_ATTEMPTS calls, the last one cut short when the window is not a multiple (its retry is refused).
     */
    private fun driveUntilOpen(
        env: RegistryEnv,
        stub: RegistryStub,
        entity: String,
        next: () -> String,
        afterCache: Int,
    ): Int {
        val failing = RegistryUseCases.BREAKER_WINDOW - afterCache
        val expected =
            List(failing / RegistryUseCases.RETRY_ATTEMPTS) { RegistryUseCases.RETRY_ATTEMPTS } +
                listOfNotNull((failing % RegistryUseCases.RETRY_ATTEMPTS).takeIf { it > 0 })
        val perLookup = mutableListOf<Int>()
        var opened = -1
        repeat(RegistryUseCases.BREAKER_WINDOW) {
            if (opened < 0) {
                val before = stub.gets()
                assertThat(env.lookup(entity, next()).status).isEqualTo(REGISTER_UNAVAILABLE)
                if (stub.gets() == before) opened = before else perLookup += stub.gets() - before
            }
        }
        assertThat(opened).describedAs("the breaker never opened").isGreaterThan(0)
        assertThat(perLookup).describedAs("calls per lookup before the breaker opened").isEqualTo(expected)
        return opened
    }

    /** Gap 1 (first call to retry) of every lookup before the breaker opened: a lower bound, and jittered. */
    private fun assertRetryGaps(times: List<Long>) {
        val gaps =
            times.chunked(RegistryUseCases.RETRY_ATTEMPTS).filter { it.size == RegistryUseCases.RETRY_ATTEMPTS }.map {
                (
                    it[1] -
                        it[0]
                ) /
                    NANOS_PER_MS
            }
        assertThat(gaps.size).describedAs("lookups before the breaker opened").isGreaterThanOrEqualTo(MIN_LOOKUPS)
        val floorMs = (1 - RegistryUseCases.JITTER) * RegistryUseCases.RETRY_BACKOFF_MS
        assertThat(gaps).allSatisfy { assertThat(it).isGreaterThanOrEqualTo(floorMs) }
        val spread = gaps.max() - gaps.min()
        assertThat(spread).describedAs("jitter spread of gap 1: $gaps").isGreaterThanOrEqualTo(MIN_SPREAD_MS)
    }

    @Test
    fun `INV-AC-2323 INV-AC-26453 the retry intervals of the edge policy are growing and jittered`() {
        val policy =
            ResiliencePolicy(
                TimeoutPolicy(Duration.ofSeconds(2)),
                RetryPolicy(
                    RegistryUseCases.RETRY_ATTEMPTS + 2,
                    Duration.ofMillis(RegistryUseCases.RETRY_BACKOFF_MS),
                    Duration.ofMillis(RegistryUseCases.RETRY_BACKOFF_MAX_MS),
                    RegistryUseCases.JITTER,
                ),
                CircuitBreakerPolicy(
                    RegistryUseCases.FAILURE_RATE,
                    RegistryUseCases.BREAKER_WINDOW,
                    Duration.ofSeconds(1),
                    RegistryUseCases.HALF_OPEN_CALLS,
                ),
                BulkheadPolicy(RegistryUseCases.BULKHEAD_CALLS, Duration.ofMillis(RegistryUseCases.BULKHEAD_WAIT_MS)),
            )
        val executor =
            Resilience4jResilientExecutor(policy, "gst-register-growth", InMemoryDeadLetterSink(), Clock.systemUTC())
        val times = mutableListOf<Long>()
        runCatching {
            executor.execute<Unit> {
                times += System.nanoTime()
                error("refused")
            }
        }
        assertThat(times).hasSize(RegistryUseCases.RETRY_ATTEMPTS + 2)
        val gaps = times.zipWithNext { a, b -> (b - a) / NANOS_PER_MS }
        val base = (1 - RegistryUseCases.JITTER) * RegistryUseCases.RETRY_BACKOFF_MS
        assertThat(gaps[0]).describedAs("gap 1 $gaps").isGreaterThanOrEqualTo(base)
        assertThat(gaps[1]).describedAs("gap 2 $gaps").isGreaterThanOrEqualTo(base * 2)
        assertThat(gaps[2]).describedAs("gap 3 $gaps").isGreaterThanOrEqualTo(base * 4)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
        const val MIN_SPREAD_MS = 5.0
        const val OPEN_LOOKUPS = 3
        const val MIN_LOOKUPS = 5
        const val CACHED_UEN = "201700001C"
    }
}
