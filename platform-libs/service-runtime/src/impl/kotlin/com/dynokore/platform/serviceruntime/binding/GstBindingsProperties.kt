package com.dynokore.platform.serviceruntime.binding

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The deployment form, the service's identity and the §12.1 binding selection
 * (ARC-11: differences are configuration, not a code branch). Bound from
 * `gst.form`, `gst.service`, `gst.instance-id` and `gst.bindings.*`, the names
 * TPL-16 sets (`gst.service`; `values-private.yaml`'s `global.form` /
 * `global.bindings`).
 *
 * @property service the service's name (TPL-16 `gst.service`), stamped on the envelopes and audit entries the
 *   composition root's writers produce (INV-T-2875).
 * @property instanceId this instance (the envelope's `instanceid`); defaults to the pod's `HOSTNAME`.
 */
@ConfigurationProperties(prefix = "gst")
data class GstProperties(
    val form: DeploymentForm = DeploymentForm.SAAS,
    val service: String? = null,
    val instanceId: String? = null,
    val bindings: BindingsProperties = BindingsProperties(),
) {
    fun requireService(): String = checkNotNull(service?.takeIf { it.isNotBlank() }) { "gst.service must name the service (TPL-16)" }

    fun requireInstanceId(): String = checkNotNull(instanceId?.takeIf { it.isNotBlank() }) { "gst.instance-id (or HOSTNAME) must name this instance" }
}

/**
 * The implementation selected per §12.1 binding (TPL-16 `global.bindings`: `eventBinding: postgres`,
 * `objectStore: s3-compatible`, `secretStore: kubernetes`). Each value names the one implementation platform-libs
 * ships for that binding today, which is also the default (INV-T-2875).
 */
data class BindingsProperties(
    val eventBinding: String = "postgres",
    val objectStore: String = "s3-compatible",
    val secretStore: SecretStoreProperties = SecretStoreProperties(),
)

/**
 * @property implementation `kubernetes`: [MountedFileSecretStore] over the mounted secret volume.
 * @property mountRoot the directory this service's own secret volume is mounted at
 *   (STK-29). Defaults to the path every service's Helm-templated pod spec mounts its
 *   vault volume at.
 */
data class SecretStoreProperties(
    val implementation: String = "kubernetes",
    val mountRoot: String = "/mnt/secrets",
)
