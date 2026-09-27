package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds one [Resilience4jResilientEdge] per edge name the first time it is
 * asked for (STK-24), from [properties]`.edges[edge]` or, absent that,
 * [properties]`.default` — never a second edge instance for the same name
 * (ARC-08's "a second client for the same edge" anti-pattern), and never
 * rebuilt: [Resilience4jResilientEdge] holds its own Resilience4j breaker and
 * bulkhead state, which a call must keep seeing across requests to mean
 * anything.
 */
class Resilience4jResilienceRegistry(
    private val properties: ResilienceProperties,
    private val clock: Clock,
) : ResilienceRegistry,
    AutoCloseable {
    private val edges = ConcurrentHashMap<String, Resilience4jResilientEdge>()

    override fun forEdge(edge: String): ResilientEdge = edges.computeIfAbsent(edge, ::newEdge)

    private fun newEdge(edge: String): Resilience4jResilientEdge {
        val edgeProperties = properties.edges[edge] ?: properties.default
        return Resilience4jResilientEdge(edge, edgeProperties.toPolicy(), clock)
    }

    override fun close() {
        edges.values.forEach(Resilience4jResilientEdge::close)
    }
}
