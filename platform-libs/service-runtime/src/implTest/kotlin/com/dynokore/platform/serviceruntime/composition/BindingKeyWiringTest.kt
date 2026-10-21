package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.binding.FakeSecretStore
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.binding.SecretStoreBindingKeySource
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import javax.sql.DataSource

/** INV-T-5360: the transaction runner's binding key is read through SecretStore; no value reaches a log line. */
@ExtendWith(OutputCaptureExtension::class)
class BindingKeyWiringTest {
    private val current = SecretStoreBindingKeySource.CURRENT
    private val previous = SecretStoreBindingKeySource.PREVIOUS

    private fun runner(store: SecretStore?): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TenancyKernelWiring::class.java)
            .withBean("dataSource", DataSource::class.java, { mockk<DataSource>() }, { it.isPrimary = true })
            .let { r -> if (store == null) r else r.withBean(SecretStore::class.java, { store }) }

    @Test
    fun `a rotated secret changes the key at the next read, with no restart`() {
        val store = FakeSecretStore().apply { put(current, "key-one") }
        runner(store).run { ctx ->
            ctx.getBean(TransactionRunner::class.java).shouldNotBeNull()
            val source = ctx.getBean(InstallationBindingKey::class.java).source
            source.currentKey()!!.decodeToString() shouldBe "key-one"
            source.previousKey().shouldBeNull() // a missing previous key is accepted

            store.put(previous, "key-one")
            store.put(current, "key-two")
            source.currentKey()!!.decodeToString() shouldBe "key-two"
            source.previousKey()!!.decodeToString() shouldBe "key-one"
        }
    }

    @Test
    fun `a start without the current secret fails naming the secret and no value`() {
        runner(FakeSecretStore().apply { put(previous, "only-previous-value") }).run { ctx ->
            val failure = ctx.startupFailure.shouldNotBeNull()
            generateSequence<Throwable>(failure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                current.value
            generateSequence<Throwable>(failure) { it.cause }
                .joinToString { it.message.orEmpty() } shouldNotContain "only-previous-value"
        }
    }

    @Test
    fun `a start with no SecretStore at all fails naming the secret`() {
        runner(null).run { ctx ->
            val failure = ctx.startupFailure.shouldNotBeNull()
            generateSequence<Throwable>(failure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                current.value
        }
    }

    @Test
    fun `the secret name appears in the log but the value never does`(output: CapturedOutput) {
        val store = FakeSecretStore().apply { put(current, "sup3r-secret-value") }
        runner(store).run { ctx ->
            val source = ctx.getBean(InstallationBindingKey::class.java).source
            source.currentKey().shouldNotBeNull()
            source.toString() shouldContain current.value
        }
        output.all shouldNotContain "sup3r-secret-value"
        // look-alike: a log line that does carry the value fails the same assertion
        val leaked = "warn sup3r-secret-value"
        (runCatching { leaked shouldNotContain "sup3r-secret-value" }.isFailure) shouldBe true
    }

    @Test
    fun `the key is the UTF-8 bytes of the value with no decoding and no trimming`() {
        val store = FakeSecretStore()
        val source = SecretStoreBindingKeySource(store)
        val hex = "ab".repeat(32)
        store.put(current, hex)
        source.currentKey().contentEquals(hex.toByteArray(Charsets.UTF_8)) shouldBe true // 64 bytes, not 32 decoded
        source.currentKey()!!.size shouldBe 64
        store.put(current, "k\u00e9y-\u00fc ")
        source.currentKey().contentEquals("k\u00e9y-\u00fc ".toByteArray(Charsets.UTF_8)) shouldBe true
        source.currentKey()!!.also { it[0] = 0 } // a copy: the next read is unaffected
        source.currentKey()!![0] shouldBe 'k'.code.toByte()
    }

    @Test
    fun `an empty or white-space-only value is no key`() {
        val store = FakeSecretStore()
        val source = SecretStoreBindingKeySource(store)
        for (blank in listOf("", " ", "\t\n ", "\u00a0")) {
            store.put(current, blank)
            store.put(previous, blank)
            source.currentKey().shouldBeNull()
            source.previousKey().shouldBeNull()
            shouldThrow<IllegalStateException> { InstallationBindingKey.of(store) }
        }
    }

    private fun withoutDataSource(store: SecretStore?): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TenancyKernelWiring::class.java)
            .let { r -> if (store == null) r else r.withBean(SecretStore::class.java, { store }) }

    private fun messages(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() }

    @Test
    fun `no DataSource and no SecretStore starts with no key and no runner`() {
        withoutDataSource(null).run { ctx ->
            ctx.startupFailure.shouldBeNull()
            ctx.getBeanNamesForType(InstallationBindingKey::class.java).toList().shouldBeEmpty()
            ctx.getBeanNamesForType(TransactionRunner::class.java).toList().shouldBeEmpty()
        }
    }

    @Test
    fun `no DataSource and only the previous secret starts with no key and no runner`() {
        withoutDataSource(FakeSecretStore().apply { put(previous, "only-previous-value") }).run { ctx ->
            ctx.startupFailure.shouldBeNull()
            ctx.getBeanNamesForType(InstallationBindingKey::class.java).toList().shouldBeEmpty()
            ctx.getBeanNamesForType(TransactionRunner::class.java).toList().shouldBeEmpty()
        }
    }

    @Test
    fun `look-alike - a DataSource and only the previous secret still fails naming the secret and no value`() {
        runner(FakeSecretStore().apply { put(previous, "only-previous-value") }).run { ctx ->
            val text = messages(ctx.startupFailure.shouldNotBeNull())
            text shouldContain current.value
            text shouldNotContain "only-previous-value"
        }
    }

    @Test
    fun `a DataSource and the service's own key bean leave that bean the only one`() {
        val own = InstallationBindingKey.of(FakeSecretStore().apply { put(current, "own-key") })
        runner(null).withBean(InstallationBindingKey::class.java, { own }).run { ctx ->
            ctx.startupFailure.shouldBeNull()
            ctx.getBeanNamesForType(InstallationBindingKey::class.java).size shouldBe 1
            (ctx.getBean(InstallationBindingKey::class.java) === own) shouldBe true
        }
    }

    private fun composed(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withBean(MeterRegistry::class.java, {
                SimpleMeterRegistry()
            })
            .withPropertyValues("service-runtime.workload-identity.audience=canonical-svc")

    @Test
    fun `composition - no DataSource starts with no SecretStore mount and no key or runner`() {
        composed().run { ctx ->
            ctx.startupFailure.shouldBeNull()
            ctx.getBeanNamesForType(InstallationBindingKey::class.java).toList().shouldBeEmpty()
            ctx.getBeanNamesForType(TransactionRunner::class.java).toList().shouldBeEmpty()
        }
    }

    @Test
    fun `composition - a DataSource and no current secret fails naming the secret`() {
        composed()
            .withBean("dataSource", DataSource::class.java, { mockk<DataSource>() }, { it.isPrimary = true })
            .run { ctx -> messages(ctx.startupFailure.shouldNotBeNull()) shouldContain current.value }
    }
}
