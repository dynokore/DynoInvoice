package com.dynokore.platform.serviceruntime.binding

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Wires the one SDS §12.1 binding this task ships an implementation for
 * (done-when, INV-T-2871 / T27): [SecretStore], selected by configuration
 * (ARC-11) — [GstProperties.bindings]'s `mountRoot`, not a second implementation,
 * since STK-29 needs only one code path for both deployment forms.
 *
 * The other bindings are bound in `composition/` (INV-T-2875, INV-Q-3710, INV-Q-3732): outbox-and-queue's
 * `EventBinding` and evidence-store's `ObjectStore` to their selected implementation, adapter-runtime's
 * `SecretStore` adapted to this one; [IdentityProvider], [PackSource], [TelemetryExport], [MessageDelivery]
 * and the base `LicenceCheck` have no platform-libs implementation and are the owning service's.
 */
@AutoConfiguration
@EnableConfigurationProperties(GstProperties::class)
class BindingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SecretStore::class)
    @ConditionalOnProperty(
        name = ["gst.bindings.secret-store.implementation"],
        havingValue = "kubernetes",
        matchIfMissing = true,
    )
    fun secretStore(properties: GstProperties): SecretStore =
        MountedFileSecretStore(Path.of(properties.bindings.secretStore.mountRoot))
}
