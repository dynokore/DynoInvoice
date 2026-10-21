package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.MutableClock
import com.dynokore.gst.gatewayauthz.revocation.Entries
import com.dynokore.gst.gatewayauthz.revocation.TokenIdentity
import com.dynokore.gst.gatewayauthz.staleness.KeyMaterialReadiness
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Duration

class KeyMaterialRefresherTest {
    private val clock = MutableClock()
    private val access = StubAccessSvc(pageSize = 2)
    private val cache = KeyMaterialCache()
    private val refresher = KeyMaterialRefresher(access, cache, clock)
    private val readiness = KeyMaterialReadiness(cache)

    private fun jti(id: String) = TokenIdentity(null, null, id, clock.now.minusSeconds(60))

    @Test
    fun `not ready until the keys and one full snapshot are loaded (INV-Q-4008 A)`() {
        readiness.health().status shouldBe Status.DOWN
        access.down = true
        refresher.refreshOnce() shouldBe false
        readiness.health().status shouldBe Status.DOWN

        access.down = false
        refresher.refreshOnce() shouldBe true
        readiness.health().status shouldBe Status.UP
        access.calls.first() shouldBe (null to null)
    }

    @Test
    fun `reads a multi-page snapshot, then deltas by sinceVersion`() {
        (1L..5L).forEach { access.revoke(Entries.token("jti-$it", clock.now, version = it)) }
        refresher.refreshOnce()
        cache.current().revocations.listVersion shouldBe 5
        cache.current().revocations.size shouldBe 5

        access.calls.clear()
        access.revoke(Entries.token("jti-6", clock.now, version = 6))
        refresher.refreshOnce()
        access.calls.first() shouldBe (5L to null)
        cache.current().revocations.listVersion shouldBe 6
        cache.current().revocations.refuses(jti("jti-1"), clock.now) shouldBe true
        cache.current().revocations.refuses(jti("jti-6"), clock.now) shouldBe true
    }

    @Test
    fun `replaces the list when access-svc answers with a snapshot (sinceVersion above its version)`() {
        (1L..3L).forEach { access.revoke(Entries.token("jti-$it", clock.now, version = it)) }
        refresher.refreshOnce()

        access.entries.clear() // access-svc restored to an earlier state
        access.revoke(Entries.token("jti-restored", clock.now, version = 1))
        refresher.refreshOnce()

        cache.current().revocations.listVersion shouldBe 1
        cache.current().revocations.refuses(jti("jti-1"), clock.now) shouldBe false
        cache.current().revocations.refuses(jti("jti-restored"), clock.now) shouldBe true
    }

    @Test
    fun `a failed refresh serves the cache and stays ready`() {
        access.revoke(Entries.token("jti-1", clock.now))
        refresher.refreshOnce()
        val freshAt = cache.current().listFreshAt

        access.down = true
        clock.advance(Duration.ofSeconds(10))
        refresher.refreshOnce() shouldBe false

        readiness.health().status shouldBe Status.UP
        cache.current().listFreshAt shouldBe freshAt
        cache.current().revocations.refuses(jti("jti-1"), clock.now) shouldBe true
    }

    @Test
    fun `expired entries are dropped at their expiresAt`() {
        access.revoke(Entries.token("jti-1", clock.now))
        refresher.refreshOnce()

        access.down = true
        clock.advance(Duration.ofMinutes(15))
        refresher.refreshOnce()

        cache.current().revocations.size shouldBe 0
    }
}
