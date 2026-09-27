package com.dynokore.platform.serviceruntime.binding

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The deployment form and the one binding this task ships a value for
 * (ARC-11: differences are configuration, not a code branch). Bound from
 * `gst.form` / `gst.bindings.secret-store.*`, the same property names TPL-16's
 * `values-private.yaml` fragment sets under `global.form` / `global.bindings`.
 */
@ConfigurationProperties(prefix = "gst")
data class GstProperties(
    val form: DeploymentForm = DeploymentForm.SAAS,
    val bindings: BindingsProperties = BindingsProperties(),
)

data class BindingsProperties(
    val secretStore: SecretStoreProperties = SecretStoreProperties(),
)

/**
 * @property mountRoot the directory this service's own secret volume is mounted at
 *   (STK-29). Defaults to the path every service's Helm-templated pod spec mounts its
 *   vault volume at.
 */
data class SecretStoreProperties(
    val mountRoot: String = "/mnt/secrets",
)
