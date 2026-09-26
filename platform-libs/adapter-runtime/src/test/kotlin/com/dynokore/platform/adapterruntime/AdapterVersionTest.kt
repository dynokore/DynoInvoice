package com.dynokore.platform.adapterruntime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdapterVersionTest {
    @Test
    fun `a well-formed value is kept as-is`() {
        AdapterVersion("2.0.0").value shouldBe "2.0.0"
    }

    @Test
    fun `toString is the plain value, for use as a Resilience4j edge or metric label`() {
        AdapterVersion("2.0.0").toString() shouldBe "2.0.0"
    }

    @Test
    fun `blank is rejected (CS-06)`() {
        shouldThrow<IllegalArgumentException> { AdapterVersion("") }
        shouldThrow<IllegalArgumentException> { AdapterVersion("   ") }
    }

    @Test
    fun `whitespace inside the value is rejected`() {
        shouldThrow<IllegalArgumentException> { AdapterVersion("v1 old") }
    }
}
