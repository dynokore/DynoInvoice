package com.dynokore.gst.payload.domain.version

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class VersionFactsTest {
    @Test
    fun `SGD currency gives the document total`() {
        totalSgdIncludingTax("SGD", "1000.01", null) shouldBe BigDecimal("1000.01")
    }

    @Test
    fun `stated SGD total wins for a foreign currency`() {
        totalSgdIncludingTax("USD", "5.00", "1000.00") shouldBe BigDecimal("1000.00")
    }

    @Test
    fun `foreign currency without an SGD total is absent`() {
        totalSgdIncludingTax("USD", "5.00", null) shouldBe null
    }

    @Test
    fun `look-alike currency sgd is absent`() {
        totalSgdIncludingTax("sgd", "1000.01", null) shouldBe null
    }

    @Test
    fun `look-alike currency with a trailing space is absent`() {
        totalSgdIncludingTax("SGD ", "1000.01", null) shouldBe null
    }

    @Test
    fun `absent currency without an SGD total is absent`() {
        totalSgdIncludingTax(null, "1000.01", null) shouldBe null
    }

    @Test
    fun `exponent text throws`() {
        assertThrows(IllegalArgumentException::class.java) { totalSgdIncludingTax("SGD", "1e3", null) }
    }

    @Test
    fun `three decimals throw`() {
        assertThrows(IllegalArgumentException::class.java) { totalSgdIncludingTax("USD", null, "1000.001") }
    }

    @Test
    fun `non-numeric text throws`() {
        assertThrows(IllegalArgumentException::class.java) { totalSgdIncludingTax("SGD", "abc", null) }
    }

    @Test
    fun `blank entityRef throws`() {
        assertThrows(IllegalArgumentException::class.java) { VersionFacts(" ", null) }
    }
}
