package com.dynokore.platform.serviceruntime.binding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OidcIssuerTest {
    @Test
    fun `jwkSetUri is optional`() {
        OidcIssuer(issuerUri = "https://idp.example.com").jwkSetUri shouldBe null
    }

    @Test
    fun `blank issuerUri is rejected`() {
        shouldThrow<IllegalArgumentException> { OidcIssuer(issuerUri = "") }
    }
}
