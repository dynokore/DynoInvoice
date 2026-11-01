package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.PackKey
import com.dynokore.gst.rules.domain.pack.PackLayer
import com.dynokore.gst.rules.domain.pack.PackLayers
import com.dynokore.gst.rules.domain.resolution.PackTypes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PackLayersTest {
    private val allTypes =
        listOf(
            PackTypes.SCOPE,
            PackTypes.CODE_CATALOGUE,
            PackTypes.MDE,
            PackTypes.SCHEMATRON,
            PackTypes.SEVERITY_OVERRIDE,
            PackTypes.SCENARIO,
            PackTypes.CALENDAR,
            PackTypes.MAPPING,
        ).map { it.value }

    @Test
    fun `INV-AC-14160 MAPPING is the tenant mapping layer`() {
        PackLayers.of("MAPPING") shouldBe PackLayer.TENANT_MAPPING
    }

    @Test
    fun `INV-AC-14160 each of the seven other pack types is regulatory`() {
        val others = allTypes.filter { it != "MAPPING" }
        others.size shouldBe 7
        others.forEach { PackLayers.of(it) shouldBe PackLayer.REGULATORY }
    }

    @Test
    fun `INV-AC-14160 look-alike spellings are regulatory`() {
        listOf("Mapping", "mapping", "MAPPING ", "RETURN_MAPPING", "MAPPINGS", "").forEach {
            PackLayers.of(it) shouldBe PackLayer.REGULATORY
        }
    }

    @Test
    fun `INV-AC-14160 a key whose layer matches its type is accepted`() {
        PackKey("MAPPING", "v1", PackLayer.TENANT_MAPPING, "t").layer shouldBe PackLayer.TENANT_MAPPING
        PackKey("SCOPE", "v1", PackLayer.REGULATORY, null).layer shouldBe PackLayer.REGULATORY
    }

    @Test
    fun `INV-AC-14160 MAPPING as a regulatory key is refused`() {
        shouldThrow<IllegalArgumentException> { PackKey("MAPPING", "v1", PackLayer.REGULATORY, null) }
    }

    @Test
    fun `INV-AC-14160 MAPPING with no tenant is refused`() {
        shouldThrow<IllegalArgumentException> { PackKey("MAPPING", "v1", PackLayer.TENANT_MAPPING, null) }
    }

    @Test
    fun `INV-AC-14160 SCOPE as a tenant mapping key is refused`() {
        shouldThrow<IllegalArgumentException> { PackKey("SCOPE", "v1", PackLayer.TENANT_MAPPING, "t") }
    }

    @Test
    fun `INV-AC-14160 the Mapping spelling as a tenant mapping key is refused`() {
        shouldThrow<IllegalArgumentException> { PackKey("Mapping", "v1", PackLayer.TENANT_MAPPING, "t") }
    }

    @Test
    fun `INV-AC-14160 pin only MAPPING among the eight pack types is tenant mapping`() {
        allTypes.size shouldBe 8
        allTypes.filter { PackLayers.of(it) == PackLayer.TENANT_MAPPING }.toSet() shouldBe setOf("MAPPING")
    }
}
