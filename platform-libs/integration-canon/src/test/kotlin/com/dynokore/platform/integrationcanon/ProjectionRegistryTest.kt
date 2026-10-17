package com.dynokore.platform.integrationcanon

import com.dynokore.platform.integrationcanon.Fixtures.a
import com.dynokore.platform.integrationcanon.Fixtures.b
import com.dynokore.platform.integrationcanon.Fixtures.c
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProjectionRegistryTest {
    private val registry =
        ProjectionRegistry
            .builder<Int, String>()
            .register(a) { "a:$it" }
            .register(b) { "b:${it * 2}" }
            .build()

    @Test
    fun `two versions registered together each return their own output`() {
        assertEquals("a:5", registry.project(a, 5))
        assertEquals("b:10", registry.project(b, 5))
        assertEquals(registry.project(a, 5), registry.project(a, 5))
    }

    @Test
    fun `duplicate registration is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionRegistry.builder<Int, String>().register(a) { "x" }.register(a) { "y" }
        }
    }

    @Test
    fun `an unregistered version throws naming it`() {
        val e = assertThrows(UnknownProjectionVersion::class.java) { registry.project(c, 1) }
        assertEquals(c, e.version)
        assertEquals(true, e.message!!.contains("spec-c"))
    }

    @Test
    fun `the registry is immutable after build`() {
        val builder = ProjectionRegistry.builder<Int, String>().register(a) { "a" }
        val built = builder.build()
        builder.register(b) { "b" }
        assertThrows(UnknownProjectionVersion::class.java) { built.project(b, 1) }
    }
}
