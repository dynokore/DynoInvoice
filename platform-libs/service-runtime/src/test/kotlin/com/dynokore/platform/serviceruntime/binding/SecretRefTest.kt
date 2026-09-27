package com.dynokore.platform.serviceruntime.binding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SecretRefTest {
    @Test
    fun `a plain name is kept as-is`() {
        SecretRef("db/owner-password").value shouldBe "db/owner-password"
    }

    @Test
    fun `blank is rejected`() {
        shouldThrow<IllegalArgumentException> { SecretRef("") }
    }

    @Test
    fun `path traversal out of the binding is rejected`() {
        shouldThrow<IllegalArgumentException> { SecretRef("../other-service/db-password") }
        shouldThrow<IllegalArgumentException> { SecretRef("a/../../b") }
        shouldThrow<IllegalArgumentException> { SecretRef("..") }
    }
}
