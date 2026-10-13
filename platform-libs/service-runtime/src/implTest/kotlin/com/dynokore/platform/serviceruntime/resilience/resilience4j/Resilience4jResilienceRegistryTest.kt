package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

/** ARC-08: one registry, one instance per edge — never a second client for the same edge. */
class Resilience4jResilienceRegistryTest {
    private val clock = Clock.systemUTC()

    @Test
    fun `returns the same edge instance for the same name`() {
        val registry = Resilience4jResilienceRegistry(ResilienceProperties(), clock)

        registry.forEdge("submission-svc->transport-svc") shouldBeSameInstanceAs
            registry.forEdge("submission-svc->transport-svc")
    }

    @Test
    fun `an edge not named in configuration gets the platform default policy`() {
        val properties = ResilienceProperties(default = EdgeProperties(timeout = Duration.ofMillis(777)))
        val registry = Resilience4jResilienceRegistry(properties, clock)

        val edge = registry.forEdge("unconfigured-edge") as Resilience4jResilientEdge
        edge.policy.timeout.timeout shouldBe Duration.ofMillis(777)
    }

    @Test
    fun `a configured edge gets its own row, not the default`() {
        val properties =
            ResilienceProperties(
                default = EdgeProperties(timeout = Duration.ofSeconds(5)),
                edges = mapOf("ingestion-svc->payload-svc" to EdgeProperties(timeout = Duration.ofMillis(150))),
            )
        val registry = Resilience4jResilienceRegistry(properties, clock)

        val edge = registry.forEdge("ingestion-svc->payload-svc") as Resilience4jResilientEdge
        edge.policy.timeout.timeout shouldBe Duration.ofMillis(150)
    }
}
