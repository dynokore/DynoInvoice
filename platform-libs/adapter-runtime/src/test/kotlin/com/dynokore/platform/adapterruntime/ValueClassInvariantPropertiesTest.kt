package com.dynokore.platform.adapterruntime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.zip
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Property tests (STK-38, CS-06): every [AdapterVersion] value class invariant holds
 * for arbitrary input, not just the handful of examples [AdapterVersionTest] checks.
 */
class ValueClassInvariantPropertiesTest {
    private val token = Arb.string(1..12, Codepoint.alphanumeric())

    @Test
    fun `any non-blank token with no whitespace is a valid AdapterVersion`() = runTest {
        checkAll(token) { value ->
            AdapterVersion(value).value shouldBe value
        }
    }

    @Test
    fun `any two tokens joined by a space are rejected as an AdapterVersion`() = runTest {
        checkAll(Arb.zip(token, token) { a, b -> "$a $b" }) { value ->
            shouldThrow<IllegalArgumentException> { AdapterVersion(value) }
        }
    }

    @Test
    fun `any non-blank token with no whitespace is a valid SecretRef`() = runTest {
        checkAll(token) { value ->
            SecretRef(value).value shouldBe value
        }
    }
}
