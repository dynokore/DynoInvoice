package com.dynokore.platform.serviceruntime.binding

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Wires the one SDS §12.1 binding this task ships an implementation for
 * (done-when, INV-T-2871 / T27): [SecretStore], selected by configuration
 * (ARC-11) — [GstProperties.bindings]'s `mountRoot`, not a second implementation,
 * since STK-29 needs only one code path for both deployment forms.
 *
 * The other five bindings declared in this module ([IdentityProvider],
 * [PackSource], [TelemetryExport], [EventBinding], [MessageDelivery]) are not
 * wired here: no implementation ships with this task (each interface's own KDoc
 * says which task does).
 */
@AutoConfiguration
@EnableConfigurationProperties(GstProperties::class)
class BindingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SecretStore::class)
    fun secretStore(properties: GstProperties): SecretStore = MountedFileSecretStore(Path.of(properties.bindings.secretStore.mountRoot))
}
