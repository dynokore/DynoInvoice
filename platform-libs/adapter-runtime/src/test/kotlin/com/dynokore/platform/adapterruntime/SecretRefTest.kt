package com.dynokore.platform.adapterruntime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SecretRefTest {
    @Test
    fun `a plain name is kept as-is`() {
        SecretRef("ingestion-svc/api-key").value shouldBe "ingestion-svc/api-key"
    }

    @Test
    fun `blank is rejected`() {
        shouldThrow<IllegalArgumentException> { SecretRef("") }
    }

    @Test
    fun `path traversal out of the binding is rejected`() {
        shouldThrow<IllegalArgumentException> { SecretRef("../other-service/api-key") }
        shouldThrow<IllegalArgumentException> { SecretRef("a/../../b") }
        shouldThrow<IllegalArgumentException> { SecretRef("..") }
    }
}
