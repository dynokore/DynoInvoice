package com.dynokore.gst.access.application.idp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdentityProviderAvailabilityTest {
    private fun run(vararg results: Boolean): IdentityProviderAvailability {
        val queue = ArrayDeque(results.toList())
        val availability = IdentityProviderAvailability("https://idp.example/realms/a", { queue.removeFirst() })
        results.forEach { availability.probeOnce() }
        return availability
    }

    private fun answers(vararg results: Boolean): List<Boolean> {
        val queue = ArrayDeque(results.toList())
        val availability = IdentityProviderAvailability("https://idp.example/realms/a", { queue.removeFirst() })
        return results.map { availability.probeOnce() }
    }

    @Test
    fun `before any probe the provider is available`() {
        assertEquals(true, run().isAvailable())
    }

    @Test
    fun `two failed probes keep it available`() {
        assertEquals(listOf(true, true), answers(false, false))
    }

    @Test
    fun `the third consecutive failed probe makes it unavailable`() {
        assertEquals(listOf(true, true, false), answers(false, false, false))
    }

    @Test
    fun `one success after unavailable makes it available`() {
        assertEquals(listOf(true, true, false, true), answers(false, false, false, true))
    }

    @Test
    fun `two failures then a success reset the count`() {
        assertEquals(listOf(true, true, true, true, true), answers(false, false, true, false, false))
    }

    @Test
    fun `a probe that throws counts as failed`() {
        val availability = IdentityProviderAvailability("i", { error("boom") })
        assertEquals(listOf(true, true, false), List(3) { availability.probeOnce() })
    }

    @Test
    fun `components for different issuer URIs do not share the count`() {
        val a = IdentityProviderAvailability("https://idp.example/realms/a", { false })
        val b = IdentityProviderAvailability("https://idp.example/realms/b", { true })
        repeat(3) {
            a.probeOnce()
            b.probeOnce()
        }
        assertEquals(
            listOf("https://idp.example/realms/a", "https://idp.example/realms/b"),
            listOf(a.issuerUri, b.issuerUri),
        )
        assertEquals(listOf(false, true), listOf(a.isAvailable(), b.isAvailable()))
    }

    @Test
    fun `the failure limit is a constructor value`() {
        val availability = IdentityProviderAvailability("i", { false }, failureLimit = 1)
        assertEquals(false, availability.probeOnce())
    }
}
