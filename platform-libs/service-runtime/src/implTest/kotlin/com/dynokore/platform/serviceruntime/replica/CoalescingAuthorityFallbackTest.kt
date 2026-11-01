package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** INV-AC-9090: calls are counted at a stub fallback held by a latch. */
class CoalescingAuthorityFallbackTest {
    private val clock = Clock.systemUTC()
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())
    private val meters = SimpleMeterRegistry()
    private val calls = AtomicInteger()
    private val entered = CountDownLatch(1)
    private var release = CountDownLatch(1)
    private var answer: () -> FallbackResult<AuthorityVerdict> = { FallbackResult.Found(AuthorityVerdict(true)) }
    private val stub =
        ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            answer()
        }
    private val subject = CoalescingAuthorityFallback(stub, clock, meters)
    private val pool = Executors.newFixedThreadPool(64)

    private fun request(
        tenant: TenantId = tenantA,
        entityRef: String = "E1",
        action: String = "X",
    ) = AuthorizationRequest(tenant, Principal("p1", PrincipalKind.HUMAN), entityRef, action)

    private fun deadline(seconds: Long = 10) = Deadline.after(Duration.ofSeconds(seconds), clock)

    private fun submit(
        request: AuthorizationRequest,
        deadline: Deadline = deadline(),
    ): Future<FallbackResult<AuthorityVerdict>> =
        pool.submit<FallbackResult<AuthorityVerdict>> {
            subject.fetch(request, deadline)
        }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
    }

    private fun coalesced() = meters.counter(CoalescingAuthorityFallback.COALESCED_METER).count()

    private fun releaseAndGet(
        futures: List<Future<FallbackResult<AuthorityVerdict>>>,
    ): List<FallbackResult<AuthorityVerdict>> {
        release.countDown()
        return futures.map { it.get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `50 equal requests reach the stub once and all get its answer`() {
        val futures = mutableListOf(submit(request()))
        entered.await(10, TimeUnit.SECONDS)
        repeat(49) { futures += submit(request()) }
        waitFor { coalesced() >= 49.0 }
        val results = releaseAndGet(futures)
        calls.get() shouldBe 1
        results.all { it == FallbackResult.Found(AuthorityVerdict(true)) } shouldBe true
        coalesced() shouldBe 49.0
    }

    @Test
    fun `the same 50 split over two actions reach the stub twice`() {
        val futures = mutableListOf(submit(request(action = "X")), submit(request(action = "Y")))
        waitFor { calls.get() == 2 }
        repeat(24) { futures += submit(request(action = "X")) }
        repeat(24) { futures += submit(request(action = "Y")) }
        waitFor { coalesced() >= 48.0 }
        releaseAndGet(futures)
        calls.get() shouldBe 2
    }

    @Test
    fun `another entityRef, another tenant and a call after return are separate calls`() {
        val futures = listOf(submit(request()), submit(request(entityRef = "E2")), submit(request(tenant = tenantB)))
        waitFor { calls.get() == 3 }
        releaseAndGet(futures)
        calls.get() shouldBe 3
        subject.fetch(request(), deadline())
        calls.get() shouldBe 4
        coalesced() shouldBe 0.0
    }

    @Test
    fun `a failing call gives every waiter Unavailable and the next call is new`() {
        answer = { throw IllegalStateException("boom") }
        val futures = mutableListOf(submit(request()))
        entered.await(10, TimeUnit.SECONDS)
        repeat(4) { futures += submit(request()) }
        waitFor { coalesced() >= 4.0 }
        releaseAndGet(futures).all { it == FallbackResult.Unavailable } shouldBe true
        calls.get() shouldBe 1
        answer = { FallbackResult.NotFound }
        subject.fetch(request(), deadline()) shouldBe FallbackResult.NotFound
        calls.get() shouldBe 2
    }

    @Test
    fun `a waiter with an expired deadline returns Unavailable while another still gets the answer`() {
        val leader = submit(request())
        entered.await(10, TimeUnit.SECONDS)
        val patient = submit(request())
        val expired = submit(request(), Deadline.after(Duration.ofMillis(50), clock))
        expired.get(10, TimeUnit.SECONDS) shouldBe FallbackResult.Unavailable
        release.countDown()
        patient.get(10, TimeUnit.SECONDS) shouldBe FallbackResult.Found(AuthorityVerdict(true))
        leader.get(10, TimeUnit.SECONDS) shouldBe FallbackResult.Found(AuthorityVerdict(true))
        calls.get() shouldBe 1
    }
}
