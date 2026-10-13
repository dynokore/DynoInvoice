package com.dynokore.platform.serviceruntime.binding

import java.nio.file.Files
import java.nio.file.Path

/**
 * The one [SecretStore] this module ships (STK-29): every [ref] names a file
 * beneath [mountRoot], the same file however it got there — an External Secrets
 * Operator sync in the SaaS form, or a Kubernetes Secret volume in the private
 * form (one code path for both, STK-29). [mountRoot] is this service's own
 * volume mount, named by its own Helm values entry — no other service's pod has
 * it mounted, which is how "a service can read only its own secret paths"
 * (FR-AH.20, this task's done-when) holds operationally, not only structurally.
 *
 * [read] never caches: it opens and reads [ref]'s file fresh on every call, so a
 * file rotated in place (the ESO / kubelet rotation mechanism) is visible on the
 * very next read, with no restart and no explicit file-watch machinery needed
 * here.
 */
class MountedFileSecretStore(
    private val mountRoot: Path,
) : SecretStore {
    override fun read(ref: SecretRef): Secret {
        val path = mountRoot.resolve(ref.value).normalize()
        require(path.startsWith(mountRoot.normalize())) { "SecretRef escaped the mount root: '$ref'" }
        if (!Files.isRegularFile(path)) throw SecretNotFound(ref)
        return Secret(Files.readString(path).trimEnd('\n', '\r'))
    }
}
