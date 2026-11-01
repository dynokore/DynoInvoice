package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.application.structure.NodeType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** INV-AC-15381: the replay cursor orders the kinds TENANT, GROUP, ENTITY, BUSINESS_UNIT (INV-Q-15377 A). */
class ReplayCursorTenantTest {
    @Test
    fun `INV-AC-15381 a TENANT cursor decodes and round-trips`() {
        val cursor = ReplayCursor.decode("TENANT|Acme")
        cursor shouldBe ReplayCursor(NodeType.TENANT, "Acme")
        cursor.encode() shouldBe "TENANT|Acme"
    }

    @Test
    fun `INV-AC-15381 every other text stays refused as malformed`() {
        listOf(
            "tenant|Acme",
            "TENANT|",
            "TENANT",
            "|Acme",
            "TENANT2|Acme",
            "Tenant|Acme",
            " TENANT|Acme",
            "ROOT|Acme",
            "",
        ).forEach { text ->
            assertThrows<IllegalArgumentException>(text) { ReplayCursor.decode(text) }
        }
    }

    @Test
    fun `INV-AC-15381 the other three kinds still decode`() {
        listOf("GROUP", "ENTITY", "BUSINESS_UNIT").forEach {
            ReplayCursor.decode("$it|x") shouldBe ReplayCursor(NodeType.valueOf(it), "x")
        }
    }
}
