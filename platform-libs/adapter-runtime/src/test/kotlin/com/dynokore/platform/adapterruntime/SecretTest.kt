package com.dynokore.platform.adapterruntime

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SecretTest {
    @Test
    fun `reveal returns exactly the wrapped value`() {
        Secret("hunter2").reveal() shouldBe "hunter2"
    }

    @Test
    fun `toString never contains the raw value (CS-24)`() {
        val secret = Secret("hunter2")
        secret.toString() shouldNotBe "hunter2"
        secret.toString() shouldBe "Secret(***)"
    }

    @Test
    fun `an interpolated string template does not leak the value either`() {
        val secret = Secret("hunter2")
        "credential=$secret" shouldBe "credential=Secret(***)"
    }
}
