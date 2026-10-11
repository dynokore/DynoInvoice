package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.adapterruntime.ConnectionStatusReporter
import com.dynokore.platform.adapterruntime.DeadLetterSink
import com.dynokore.platform.adapterruntime.Secret
import com.dynokore.platform.adapterruntime.SecretNotFound
import com.dynokore.platform.adapterruntime.SecretRef
import com.dynokore.platform.adapterruntime.SecretStore
import com.dynokore.platform.adapterruntime.connection.InMemoryConnectionStatusReporter
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.dynokore.platform.serviceruntime.binding.SecretNotFound as BoundSecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef as BoundSecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore as BoundSecretStore

/**
 * adapter-runtime's ports (INV-T-2875, INV-Q-3710, INV-Q-3730), when the service embeds its impl half:
 * - its [SecretStore] is adapted to service-runtime's bound `SecretStore` (`MountedFileSecretStore`), so both
 *   modules read the one selected binding;
 * - its version-keyed [ConnectionStatusReporter] is a local status bean of its own and is **not** bridged:
 *   `ConnectionStatusChanged` is published only through service-runtime's reporter (ConnectionRecord and breaker
 *   state, INV-REQ-836 b4), and adapter-runtime's UNKNOWN is never published (INV-Q-3730);
 * - its [DeadLetterSink].
 * A `ResilientExecutor` and a `VersionedAdapterRouter` are per edge and per adapter, built by the service.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.adapterruntime.connection.InMemoryConnectionStatusReporter"])
class AdapterRuntimeWiring {
    @Bean
    @ConditionalOnBean(BoundSecretStore::class)
    @ConditionalOnMissingBean(SecretStore::class)
    fun adapterSecretStore(bound: BoundSecretStore): SecretStore = BoundSecretStoreAdapter(bound)

    @Bean
    @ConditionalOnMissingBean(ConnectionStatusReporter::class)
    fun adapterConnectionStatusReporter(): InMemoryConnectionStatusReporter = InMemoryConnectionStatusReporter()

    @Bean
    @ConditionalOnMissingBean(DeadLetterSink::class)
    fun adapterDeadLetterSink(): InMemoryDeadLetterSink = InMemoryDeadLetterSink()
}

/** adapter-runtime's [SecretStore] over service-runtime's bound one: the same reference, the same secret. */
internal class BoundSecretStoreAdapter(
    private val bound: BoundSecretStore,
) : SecretStore {
    override fun read(ref: SecretRef): Secret = try {
        Secret(bound.read(BoundSecretRef(ref.value)).reveal())
    } catch (_: BoundSecretNotFound) {
        throw SecretNotFound(ref)
    }
}
