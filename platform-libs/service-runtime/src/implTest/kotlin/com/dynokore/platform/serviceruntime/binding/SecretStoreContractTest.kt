package com.dynokore.platform.serviceruntime.binding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The port contract (CS-43) every [SecretStore] implementation must satisfy,
 * fake or real. Subclasses provide a fresh store per test, seeded with [seedRef]
 * bound to [seedValue].
 */
abstract class SecretStoreContractTest {
    protected val seedRef = SecretRef("test-secret")
    protected val seedValue = "s3cr3t-value"

    /** A fresh implementation, with [seedRef] already bound to [seedValue]. */
    abstract fun store(): SecretStore

    /** A ref no implementation has bound anything to. */
    abstract fun unboundRef(): SecretRef

    @Test
    fun `read returns the bound value`() {
        store().read(seedRef).reveal() shouldBe seedValue
    }

    @Test
    fun `read throws SecretNotFound for an unbound ref`() {
        val thrown = shouldThrow<SecretNotFound> { store().read(unboundRef()) }
        thrown.ref shouldBe unboundRef()
    }

    @Test
    fun `two reads of the same ref return the same value`() {
        val store = store()
        store.read(seedRef).reveal() shouldBe store.read(seedRef).reveal()
    }
}
