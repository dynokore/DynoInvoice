package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JacksonMasterDataResolvedReaderTest {
    private val reader = JacksonMasterDataResolvedReader()
    private val base = "\"partyRef\":\"P-1\",\"resolvedIdentifiers\":[{\"scheme\":\"UEN\"}]"

    private fun json(extra: String = "") = "{$base$extra}"

    @Test
    fun `INV-AC-21062 member absent gives an empty list`() {
        reader.read(json()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21062 JSON null member gives an empty list`() {
        reader.read(json(",\"docUuids\":null")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21062 empty array gives an empty list`() {
        reader.read(json(",\"docUuids\":[]")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21062 duplicate entry is listed once in first-seen order`() {
        reader.read(json(",\"docUuids\":[\"b\",\"a\",\"b\"]")) shouldBe listOf("b", "a")
    }

    @Test
    fun `INV-AC-21062 number entry is a contract violation`() {
        shouldThrow<ContractViolation> { reader.read(json(",\"docUuids\":[\"a\",1]")) }
    }

    @Test
    fun `INV-AC-21062 blank entry is a contract violation`() {
        shouldThrow<ContractViolation> { reader.read(json(",\"docUuids\":[\" \"]")) }
    }

    @Test
    fun `INV-AC-21062 string instead of an array is a contract violation`() {
        shouldThrow<ContractViolation> { reader.read(json(",\"docUuids\":\"a\"")) }
    }

    @Test
    fun `INV-AC-21062 non-object and non-JSON data is a contract violation`() {
        shouldThrow<ContractViolation> { reader.read("[]") }
        shouldThrow<ContractViolation> { reader.read("not json") }
    }

    @Test
    fun `INV-AC-21062 missing partyRef is a contract violation`() {
        shouldThrow<ContractViolation> { reader.read("{\"resolvedIdentifiers\":[]}") }
    }

    @Test
    fun `INV-AC-21062 missing resolvedIdentifiers is a contract violation`() {
        shouldThrow<ContractViolation> { reader.read("{\"partyRef\":\"P-1\"}") }
    }
}
