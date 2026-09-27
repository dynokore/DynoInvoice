package com.dynokore.platform.serviceruntime.identity

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads this pod's own projected Kubernetes service-account token fresh on
 * every call (STK-26) — never cached, so the kubelet's own periodic rotation of
 * the projected file is visible on the very next call, with no restart, the
 * same discipline `MountedFileSecretStore` uses for STK-29.
 */
class ProjectedServiceAccountTokenSource(private val tokenPath: Path) : WorkloadTokenSource {
    override fun current(): WorkloadToken {
        if (!Files.isRegularFile(tokenPath)) throw WorkloadTokenUnavailable(tokenPath)
        return WorkloadToken(Files.readString(tokenPath).trim())
    }
}

/** The projected token volume is not mounted, or was not yet written — an infrastructure failure, not a refused call (CS-14). */
class WorkloadTokenUnavailable(path: Path) : RuntimeException("No projected service-account token at $path")
