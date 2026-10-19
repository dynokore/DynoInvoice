package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.signing.HsmPackSigningKeys
import com.dynokore.gst.controlPlane.adapters.signing.PackSigningSettings
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * The pack signing key (INV-Q-7459): the settings come from `control-plane.signing.*`, the PIN and the certificate
 * through the SecretStore binding by reference. Start-up fails when the key store type is not PKCS11 outside the
 * profile `test` (STK-13 rejects software-held keys), before any request is served.
 */
@Configuration(proxyBeanMethods = false)
class SigningConfiguration {
    @Bean
    fun packSigningSettings(environment: Environment): PackSigningSettings {
        val settings =
            Binder
                .get(environment)
                .bind(PackSigningSettings.PREFIX, PackSigningSettings::class.java)
                .orElseThrow { IllegalStateException("${PackSigningSettings.PREFIX} is not configured") }
        PackSigningSettings.verify(settings, environment.activeProfiles.toList())
        return settings
    }

    @Bean
    fun packSigningKeys(
        settings: PackSigningSettings,
        secrets: SecretStore,
    ): PackSigningKeys = HsmPackSigningKeys(settings, secrets)
}
