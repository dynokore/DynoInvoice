package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class TenantContextImplTest {
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    @Test
    fun `current throws before anything is bound`() {
        assertThrows(IllegalStateException::class.java) { TenantContextImpl().current() }
    }

    @Test
    fun `bind makes current available for the duration of the block`() {
        val ctx = TenantContextImpl()
        val seen = ctx.bind(tenantA, "test-actor") { ctx.current() }
        assertEquals(tenantA, seen)
    }

    @Test
    fun `bind restores nothing bound after it returns`() {
        val ctx = TenantContextImpl()
        ctx.bind(tenantA, "test-actor") { }
        assertThrows(IllegalStateException::class.java) { ctx.current() }
    }

    @Test
    fun `nested bind restores the enclosing tenant, not nothing`() {
        val ctx = TenantContextImpl()
        val seenAfterNested =
            ctx.bind(tenantA, "test-actor") {
                ctx.bind(tenantB, "test-actor") { ctx.current() }
                ctx.current()
            }
        assertEquals(tenantA, seenAfterNested)
    }

    @Test
    fun `bind restores the enclosing tenant even when the block throws`() {
        val ctx = TenantContextImpl()
        ctx.bind(tenantA, "test-actor") {
            runCatching { ctx.bind(tenantB, "test-actor") { error("boom") } }
            assertEquals(tenantA, ctx.current())
        }
    }

    @Test
    fun `a fresh thread sees nothing bound by another thread`() {
        val ctx = TenantContextImpl()
        ctx.bind(tenantA, "test-actor") {
            var otherThreadSaw: Throwable? = null
            val thread =
                Thread {
                    otherThreadSaw = runCatching { ctx.current() }.exceptionOrNull()
                }
            thread.start()
            thread.join()
            assertEquals(IllegalStateException::class.java, otherThreadSaw?.javaClass)
        }
    }

    @Test
    fun `bind with an actor exposes the actor with the tenant and restores the enclosing binding`() {
        val ctx = TenantContextImpl()
        val seen =
            ctx.bind(tenantA, "user-a") {
                val inner = ctx.bind(tenantB, "user-b") { ctx.current() to ctx.currentActor() }
                Triple(inner, ctx.current(), ctx.currentActor())
            }
        assertEquals(Triple(tenantB to "user-b", tenantA, "user-a"), seen)
        assertThrows(IllegalStateException::class.java) { ctx.currentActor() }
    }

    @Test
    fun `a blank actor is refused`() {
        val ctx = TenantContextImpl()
        assertThrows(IllegalArgumentException::class.java) { ctx.bind(tenantA, " ") { } }
        assertThrows(IllegalArgumentException::class.java) { ctx.bindPlatform("") { } }
    }

    @Test
    fun `an actor the SQL checks refuse is never bound (INV-Q-4614, INV-Q-4622)`() {
        val ctx = TenantContextImpl()
        val refused =
            listOf("\t", "\n", "\r", "\u0085", "\u00A0", "\u2003", "\u3000", "\uFEFF", " \u00A0\t\r\n") +
                listOf("a\tb", "\nx", "x\r", "x\u0085", "\u001B[31mx", "x\u009B", "x\u0000")
        val bound =
            refused.filter { actor ->
                runCatching { ctx.bind(tenantA, actor) { } }.isSuccess ||
                    runCatching { ctx.bindPlatform(actor) { } }.isSuccess
            }
        assertEquals(emptyList<String>(), bound)
        listOf("x\u200By", "CASE 5521", " user-a ", "u".repeat(255)).forEach { actor ->
            assertEquals(actor, ctx.bind(tenantA, actor) { ctx.currentActor() })
            assertEquals(actor, ctx.bindPlatform(actor) { ctx.currentActor() })
        }
    }

    @Test
    fun `the actor-bound bind refuses the platform tenant, bindPlatform with an actor binds it`() {
        val ctx = TenantContextImpl()
        assertThrows(IllegalArgumentException::class.java) { ctx.bind(TenantId.PLATFORM, "svc:x/job:y") { } }
        val seen = ctx.bindPlatform("svc:x/job:y") { ctx.current() to ctx.currentActor() }
        assertEquals(TenantId.PLATFORM to "svc:x/job:y", seen)
    }
}
