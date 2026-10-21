package com.dynokore.gst.gatewayauthz.refresh

import com.dynokore.gst.gatewayauthz.revocation.Entries
import com.dynokore.gst.gatewayauthz.revocation.TokenIdentity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** INV-AC-936, INV-Q-3973 A: a token revoked while the gateway runs is refused within one second (real schedule). */
class RevocationWithinOneSecondTest {
    @Test
    fun `a token revoked during the test is refused within one second`() {
        val clock = Clock.systemUTC()
        val access = StubAccessSvc()
        val cache = KeyMaterialCache()
        val schedule = KeyMaterialRefreshSchedule(KeyMaterialRefresher(access, cache, clock), Duration.ofMillis(500))
        schedule.afterPropertiesSet()
        try {
            awaitUntil(Duration.ofSeconds(5)) { cache.loaded }
            val token = TokenIdentity("PRN-00088", "erp-client", "jti-1", Instant.now().minusSeconds(30))
            cache.current().revocations.refuses(token, clock.instant()) shouldBe false

            access.revoke(Entries.principal("PRN-00088", Instant.now(), version = 1))
            val revokedAt = System.nanoTime()
            awaitUntil(Duration.ofSeconds(2)) { cache.current().revocations.refuses(token, clock.instant()) }
            val elapsed = Duration.ofNanos(System.nanoTime() - revokedAt)

            (elapsed <= Duration.ofSeconds(1)) shouldBe true
        } finally {
            schedule.destroy()
        }
    }

    private fun awaitUntil(
        limit: Duration,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + limit.toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.onSpinWait()
        condition() shouldBe true
    }
}
