package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class WorkloadTokenTest {
    @Test
    fun `rejects a blank value`() {
        shouldThrow<IllegalArgumentException> { WorkloadToken("") }
        shouldThrow<IllegalArgumentException> { WorkloadToken("   ") }
    }

    @Test
    fun `toString never reveals the token itself (CS-34)`() {
        WorkloadToken("super-secret-jwt").toString() shouldNotContain "super-secret-jwt"
    }

    @Test
    fun `equality is by value`() {
        WorkloadToken("same") shouldBe WorkloadToken("same")
    }
}
