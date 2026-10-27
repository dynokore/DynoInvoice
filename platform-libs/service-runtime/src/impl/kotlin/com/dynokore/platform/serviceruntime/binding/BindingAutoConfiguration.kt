package com.dynokore.platform.serviceruntime.binding

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import java.nio.file.Path

/**
 * Wires the two SDS §12.1 bindings this module ships an implementation for, each selected by configuration
 * (ARC-11), not by a second implementation:
 * - [SecretStore] (INV-T-2871 / T27): [GstProperties.bindings]'s `mountRoot`, since STK-29 needs only one code path
 *   for both deployment forms.
 * - [TelemetryExport] (INV-T-11067, INV-Q-11058): [ConfiguredTelemetryExport], created when
 *   `gst.bindings.telemetry-export` is set and no other [TelemetryExport] bean exists. With the property absent none
 *   is provided: a service may supply its own.
 *
 * The other bindings are bound in `composition/` (INV-T-2875, INV-Q-3710, INV-Q-3732): outbox-and-queue's
 * `EventBinding` and evidence-store's `ObjectStore` to their selected implementation, adapter-runtime's
 * `SecretStore` adapted to this one; [IdentityProvider], [PackSource], [MessageDelivery]
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

    @Bean
    @ConditionalOnMissingBean(TelemetryExport::class)
    @Conditional(TelemetryExportSelected::class)
    fun telemetryExport(properties: GstProperties): TelemetryExport =
        ConfiguredTelemetryExport(
            properties.bindings.telemetryExport,
            properties.bindings.telemetryExportOtlpEndpoint,
        )

    /**
     * True when `gst.bindings.telemetry-export` is set to any value, so a wrong value (`false`, empty) is refused at
     * start-up rather than read as "not selected", which `ConditionalOnProperty` without a value would do for `false`.
     */
    internal class TelemetryExportSelected : SpringBootCondition() {
        override fun getMatchOutcome(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ): ConditionOutcome =
            if (context.environment.containsProperty(ConfiguredTelemetryExport.SELECTION_PROPERTY)) {
                ConditionOutcome.match("${ConfiguredTelemetryExport.SELECTION_PROPERTY} is set")
            } else {
                ConditionOutcome.noMatch("${ConfiguredTelemetryExport.SELECTION_PROPERTY} is not set")
            }
    }
}
