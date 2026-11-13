package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JacksonExceptionReadersTest {
    private val held = JacksonDocumentHeldReader()
    private val released = JacksonDocumentReleasedReader()
    private val doc = "22222222-2222-2222-2222-222222222222"

    private fun heldJson(
        vararg drop: String,
        override: Map<String, String> = emptyMap(),
    ): String {
        val members =
            linkedMapOf(
                "docUuid" to "\"$doc\"",
                "docsequence" to "1",
                "holdReasons" to "[\"PAY-001\",\"MDM-002\"]",
                "entityRef" to "\"ENT-PAYLOAD\"",
                "prescribedPeriodRef" to "\"2026-Q3\"",
                "code" to "\"PAY-001\"",
                "remediationOwner" to "\"Tax operations\"",
                "disposition" to "\"MANUAL\"",
            )
        members.putAll(override)
        drop.forEach { members.remove(it) }
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private fun releasedJson(
        vararg drop: String,
        override: Map<String, String> = emptyMap(),
    ): String {
        val members =
            linkedMapOf(
                "docUuid" to "\"$doc\"",
                "docsequence" to "2",
                "releasedHoldReasons" to "[\"PAY-001\"]",
            )
        members.putAll(override)
        drop.forEach { members.remove(it) }
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun `INV-AC-21027 the full DocumentHeld payload is read`() {
        val f = held.read(heldJson(), "ENT-ENVELOPE")
        f.entityRef shouldBe "ENT-PAYLOAD"
        f.docUuid shouldBe doc
        f.docsequence shouldBe 1L
        f.holdReasons shouldBe listOf("PAY-001", "MDM-002")
        f.prescribedPeriodRef shouldBe "2026-Q3"
        f.code shouldBe "PAY-001"
        f.remediationOwner shouldBe "Tax operations"
        f.disposition shouldBe "MANUAL"
    }

    @Test
    fun `INV-AC-21027 DocumentHeld missing required member is a ContractViolation`() {
        listOf("docUuid", "docsequence", "holdReasons").forEach {
            shouldThrow<ContractViolation> { held.read(heldJson(it), "ENT-ENVELOPE") }
        }
    }

    @Test
    fun `INV-AC-21027 DocumentHeld wrongly typed required member is a ContractViolation`() {
        listOf(
            "docUuid" to "5",
            "docsequence" to "\"1\"",
            "docsequence" to "0",
            "docsequence" to "1.5",
            "holdReasons" to "\"PAY-001\"",
            "holdReasons" to "[1]",
        ).forEach { (k, v) ->
            shouldThrow<ContractViolation> { held.read(heldJson(override = mapOf(k to v)), "ENT-ENVELOPE") }
        }
    }

    @Test
    fun `INV-AC-21027 DocumentHeld empty holdReasons is a ContractViolation`() {
        shouldThrow<ContractViolation> { held.read(heldJson(override = mapOf("holdReasons" to "[]")), "E") }
    }

    @Test
    fun `INV-AC-21027 entityRef of the payload wins over a different fallback`() {
        held.read(heldJson(), "ENT-ENVELOPE").entityRef shouldBe "ENT-PAYLOAD"
    }

    @Test
    fun `INV-AC-21027 absent entityRef gives the fallback`() {
        held.read(heldJson("entityRef"), "ENT-ENVELOPE").entityRef shouldBe "ENT-ENVELOPE"
    }

    @Test
    fun `INV-AC-21027 JSON null entityRef gives the fallback`() {
        held.read(heldJson(override = mapOf("entityRef" to "null")), "ENT-ENVELOPE").entityRef shouldBe "ENT-ENVELOPE"
    }

    @Test
    fun `INV-AC-21027 absent entityRef with a null fallback is a ContractViolation`() {
        shouldThrow<ContractViolation> { held.read(heldJson("entityRef"), null) }
    }

    @Test
    fun `INV-AC-21027 a number or blank entityRef is a ContractViolation even with a fallback`() {
        listOf("5", "\"\"", "\" \"", "true", "{}").forEach {
            shouldThrow<ContractViolation> { held.read(heldJson(override = mapOf("entityRef" to it)), "ENT-ENVELOPE") }
        }
    }

    @Test
    fun `INV-AC-21027 optional DocumentHeld members absent give the empty string`() {
        val f = held.read(heldJson("code", "remediationOwner", "disposition", "prescribedPeriodRef"), "E")
        f.code shouldBe ""
        f.remediationOwner shouldBe ""
        f.disposition shouldBe ""
        f.prescribedPeriodRef shouldBe ""
    }

    @Test
    fun `INV-AC-21027 DocumentHeld data that is not a JSON object is a ContractViolation`() {
        listOf("[]", "\"x\"", "5", "null", "not json", "").forEach {
            shouldThrow<ContractViolation> { held.read(it, "ENT-ENVELOPE") }
        }
    }

    @Test
    fun `INV-AC-21027 the full DocumentReleased payload is read`() {
        val f = released.read(releasedJson())
        f.docUuid shouldBe doc
        f.docsequence shouldBe 2L
        f.releasedHoldReasons shouldBe listOf("PAY-001")
    }

    @Test
    fun `INV-AC-21027 DocumentReleased missing required member is a ContractViolation`() {
        listOf("docUuid", "docsequence", "releasedHoldReasons").forEach {
            shouldThrow<ContractViolation> { released.read(releasedJson(it)) }
        }
    }

    @Test
    fun `INV-AC-21027 DocumentReleased wrongly typed or empty member is a ContractViolation`() {
        listOf(
            "docUuid" to "5",
            "docsequence" to "\"2\"",
            "docsequence" to "0",
            "releasedHoldReasons" to "\"PAY-001\"",
            "releasedHoldReasons" to "[]",
            "releasedHoldReasons" to "[1]",
        ).forEach { (k, v) ->
            shouldThrow<ContractViolation> { released.read(releasedJson(override = mapOf(k to v))) }
        }
    }

    @Test
    fun `INV-AC-21027 DocumentReleased data that is not a JSON object is a ContractViolation`() {
        listOf("[]", "\"x\"", "5", "null", "not json", "").forEach {
            shouldThrow<ContractViolation> { released.read(it) }
        }
    }
}
