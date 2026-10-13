package com.dynokore.platform.serviceruntime.health

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import java.nio.file.Files
import java.nio.file.Path

/**
 * The vault contributor to the readiness group of FR-AH.17: "a service starts,
 * and reports itself live, with only its own schema, the event log and the
 * vault available" — this is the vault third. Checks that [mountRoot] (the
 * `SecretStore` binding's own mount, STK-29) is itself present and readable,
 * not any one named secret: a cold start with the vault down means the mount
 * is not there at all, and reporting `DOWN` for that (never `DEGRADED` — the
 * vault is one of the three things every service needs unconditionally, not a
 * domain dependency) is what makes that failure visible (§36.7) rather than a
 * silent, permanently-empty diagnostic response.
 */
class VaultHealthIndicator(
    private val mountRoot: Path,
) : HealthIndicator {
    override fun health(): Health =
        if (Files.isDirectory(mountRoot) && Files.isReadable(mountRoot)) {
            Health.up().withDetail("mountRoot", mountRoot.toString()).build()
        } else {
            Health.down().withDetail("mountRoot", mountRoot.toString()).build()
        }
}
