package com.dynokore.gst.payload.domain.finding

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StoredFindingTest {
    private fun r(
        code: String = "PAY-011",
        field: String? = "x",
        severity: String? = "BLOCK",
        overridable: Boolean? = true,
        message: String = "m",
    ) = ValidationReason(code, field, message, severity, overridable)

    private fun refused(severity: String?) {
        assertThrows(IllegalArgumentException::class.java) { StoredFinding.fromReasons(listOf(r(severity = severity))) }
    }

    @Test
    fun `severity block lower case throws`() = refused("block")

    @Test
    fun `severity BLOCK with trailing space throws`() = refused("BLOCK ")

    @Test
    fun `severity BLOCKED throws`() = refused("BLOCKED")

    @Test
    fun `severity FATAL throws`() = refused("FATAL")

    @Test
    fun `absent severity throws`() = refused(null)

    @Test
    fun `INFO and WARN are dropped and BLOCK is kept`() {
        StoredFinding.fromReasons(
            listOf(r(severity = "INFO"), r(code = "B", severity = "WARN"), r(code = "C")),
        ) shouldBe
            listOf(StoredFinding("C", "x", "m", true))
    }

    @Test
    fun `absent overridable is stored false and absent field is empty`() {
        StoredFinding.fromReasons(listOf(r(overridable = null, field = null))) shouldBe
            listOf(StoredFinding("PAY-011", "", "m", false))
    }

    @Test
    fun `same code and field merge by AND with the first message, in both orders`() {
        val a = r(overridable = true, message = "first")
        val b = r(overridable = false, message = "second")
        StoredFinding.fromReasons(listOf(a, b)) shouldBe listOf(StoredFinding("PAY-011", "x", "first", false))
        StoredFinding.fromReasons(listOf(b, a)) shouldBe listOf(StoredFinding("PAY-011", "x", "second", false))
        StoredFinding.fromReasons(listOf(a, a)) shouldBe listOf(StoredFinding("PAY-011", "x", "first", true))
    }

    @Test
    fun `same code with different fields yields two findings`() {
        StoredFinding.fromReasons(listOf(r(field = "x"), r(field = "y"))).size shouldBe 2
    }

    @Test
    fun `code of 21 characters throws and 20 is accepted`() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { StoredFinding.fromReasons(listOf(r(code = "A".repeat(21)))) }
        StoredFinding.fromReasons(listOf(r(code = "A".repeat(20)))).size shouldBe 1
    }

    @Test
    fun `empty code throws`() {
        assertThrows(IllegalArgumentException::class.java) { StoredFinding.fromReasons(listOf(r(code = ""))) }
    }
}
