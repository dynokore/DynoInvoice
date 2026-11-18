package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import java.time.Instant

/**
 * The fail-closed object store of the sealed work-papers (INV-T-24512, INV-AC-24511). It is an auto-configuration
 * ordered after the platform composition: a user configuration is registered before every auto-configuration, so a
 * fallback declared there would exist when the platform `S3ObjectStore` is evaluated and the bucket could never bind.
 * Here `@ConditionalOnMissingBean` backs off whenever the platform (or a test) supplies a store.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class ObjectStoreFallbackAutoConfiguration {
    /**
     * Fails closed until the deployment binds a real store (bucket and credentials are a deployment concern): every
     * put and get throws, so nothing is sealed and no seal row is written without its object.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStore::class)
    fun unconfiguredObjectStore(): ObjectStore = UnconfiguredObjectStore
}

/** The object store of a service that has none bound: it refuses every call. */
internal object UnconfiguredObjectStore : ObjectStore {
    override fun put(
        key: ObjectKey,
        bytes: ByteArray,
        retainUntil: Instant,
    ): ObjectRef = error(MESSAGE)

    override fun get(key: ObjectKey): ByteArray = error(MESSAGE)

    private const val MESSAGE = "no object store is configured for the sealed work-papers"
}
