package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import java.time.Instant

/**
 * The fail-closed [ObjectStore] fallback (INV-T-24510). A user configuration is registered before every
 * auto-configuration, so a fallback declared there shadows the platform `S3ObjectStore` and the bucket can never be
 * bound. As an auto-configuration ordered after the platform composition, `@ConditionalOnMissingBean` is evaluated
 * once the platform wiring has decided: the platform store wins when the object-store settings are present, and every
 * call is refused otherwise.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class ObjectStoreFallbackAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectStore::class)
    internal fun unconfiguredObjectStore(): ObjectStore = UnconfiguredObjectStore

    internal object UnconfiguredObjectStore : ObjectStore {
        const val MESSAGE = "no object store is configured"

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef = error(MESSAGE)

        override fun get(key: ObjectKey): ByteArray = error(MESSAGE)
    }
}
