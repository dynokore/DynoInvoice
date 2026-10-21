package com.dynokore.gst.gatewayauthz.staleness

import com.dynokore.gst.gatewayauthz.MutableClock
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialRefresher
import com.dynokore.gst.gatewayauthz.refresh.StubAccessSvc
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

class RevocationListStalenessTest {
    private val clock = MutableClock()
    private val access = StubAccessSvc()
    private val cache = KeyMaterialCache()
    private val refresher = KeyMaterialRefresher(access, cache, clock)
    private val staleness = RevocationListStaleness(cache, StalenessBound.DEFAULT, clock)

    @Test
    fun `access-svc down - the staleness metric fires once the list exceeds the bound and clears when fresh`() {
        val registry = SimpleMeterRegistry()
        StalenessMetrics.bind(registry, staleness)
        val stale = { registry.get(StalenessMetrics.STALE).gauge().value() }
        refresher.refreshOnce()
        access.down = true

        clock.advance(Duration.ofSeconds(30))
        refresher.refreshOnce()
        stale() shouldBe 0.0

        clock.advance(Duration.ofMillis(1))
        refresher.refreshOnce()
        stale() shouldBe 1.0
        cache.loaded shouldBe true // machine tokens keep verifying from the cache

        access.down = false
        refresher.refreshOnce()
        stale() shouldBe 0.0
    }

    @Test
    fun `the staleness metric is 1 while the list is stale and 0 once it is fresh`() {
        val registry = SimpleMeterRegistry()
        StalenessMetrics.bind(registry, staleness)
        val stale = { registry.get(StalenessMetrics.STALE).gauge().value() }

        stale() shouldBe 1.0
        refresher.refreshOnce()
        stale() shouldBe 0.0
        registry.get(StalenessMetrics.AGE).gauge().value() shouldBe 0.0

        clock.advance(Duration.ofSeconds(31))
        stale() shouldBe 1.0
    }

    @Test
    fun `the bound may only be tightened`() {
        StalenessBound(Duration.ofSeconds(10)).value shouldBe Duration.ofSeconds(10)
        shouldThrow<IllegalArgumentException> { StalenessBound(Duration.ofSeconds(31)) }
        shouldThrow<IllegalArgumentException> { StalenessBound(Duration.ZERO) }
    }
}
