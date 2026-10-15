package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * INV-T-3999 (coordinator ruling INV-Q-3997): the actor-less binding is gone. Deny-by-default from the resolved
 * API surface: every public `bind*` method of the two binding ports takes the actor, so an overload added later
 * without one fails here.
 */
class ActorBindingApiTest {
    @Test
    fun `every binding method of the api takes an actor`() {
        val actorless =
            listOf(TenantContext::class.java, PlatformTenantBinding::class.java)
                .flatMap { type -> type.methods.filter { it.name.startsWith("bind") }.map { type to it } }
                .filter { (_, method) -> method.parameterTypes.none { it == String::class.java } }
                .map { (type, method) -> "${type.simpleName}.${method.name}" }
        assertEquals(emptyList<String>(), actorless)
    }

    @Test
    fun `the api still offers both actor-bound forms`() {
        assertEquals(
            1,
            TenantContext::class.java.methods.count {
                it.name.startsWith("bind") &&
                    it.name != "bindPlatform"
            },
        )
        assertEquals(1, PlatformTenantBinding::class.java.methods.count { it.name.startsWith("bindPlatform") })
    }
}
