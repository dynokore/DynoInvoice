package com.dynokore.gst.tenancy.application.installation

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The DER certificates a licence signer must equal (INV-Q-9821 A); the default binding holds none. */
fun interface LicenceTrustAnchors {
    fun certificatesDer(): List<ByteArray>

    companion object {
        /** Every file is refused. */
        val NONE = LicenceTrustAnchors { emptyList() }
    }
}

enum class LicenceStatus { ACTIVE, EXPIRED }

/** The members of a verified licence file. */
data class LicenceFileContent(
    val licenceRef: String,
    val installationRef: String,
    val term: String,
    val issuedAt: String,
)

/** Verifies a signed licence file and returns its content, or the reason it is refused (fail closed). */
fun interface LicenceFileVerifier {
    fun verify(
        licenceId: String,
        signedFile: ByteArray,
    ): LicenceVerification
}

sealed interface LicenceVerification {
    data class Valid(
        val content: LicenceFileContent,
    ) : LicenceVerification

    data class Refused(
        val reason: String,
    ) : LicenceVerification
}

data class InstalledLicence(
    val licenceId: String,
    val status: LicenceStatus,
    val expiresAt: LocalDate,
    val installedAt: Instant,
)

/** The licences installed on the installation, none replaced (installation-scoped, no tenant). */
interface LicenceStore {
    /** Keeps the first record of a licence id; returns the stored record. */
    fun putIfAbsent(licence: InstalledLicence): InstalledLicence

    fun latest(): InstalledLicence?

    fun count(): Int
}

sealed interface InstallOutcome {
    data class Installed(
        val licence: InstalledLicence,
    ) : InstallOutcome

    data class Refused(
        val reason: String,
    ) : InstallOutcome
}

/**
 * installLicence and getLicenceStatus (INV-REQ-1076, INV-REQ-907, INV-Q-9821 A). An expired licence raises DEP-005 as a
 * warning; a valid one clears it; neither blocks anything.
 */
@Suppress("LongParameterList") // one collaborator per port the installation touches (verifier, store, health, clock)
class Licences(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val verifier: LicenceFileVerifier,
    private val store: LicenceStore,
    private val health: InstallationHealth,
    private val clock: () -> Instant,
    private val actor: String,
) {
    fun install(
        licenceId: String,
        signedFile: ByteArray,
    ): InstallOutcome =
        when (val verified = verifier.verify(licenceId, signedFile)) {
            is LicenceVerification.Refused -> InstallOutcome.Refused(verified.reason)
            is LicenceVerification.Valid -> record(licenceId, verified.content)
        }

    private fun record(
        licenceId: String,
        content: LicenceFileContent,
    ): InstallOutcome {
        val end = endOf(content.term) ?: return InstallOutcome.Refused("term is not yyyy-MM-dd/yyyy-MM-dd")
        return platform.bindPlatform(actor) {
            tx.inTransaction {
                val now = clock()
                val lapsed = end.isBefore(now.atZone(ZoneOffset.UTC).toLocalDate())
                val status = if (lapsed) LicenceStatus.EXPIRED else LicenceStatus.ACTIVE
                val stored = store.putIfAbsent(InstalledLicence(licenceId, status, end, now))
                val current = checkNotNull(store.latest()) // the status follows the latest installed licence
                val expired = current.status == LicenceStatus.EXPIRED
                health.setState(
                    DepCode.DEP_005,
                    expired,
                    if (expired) {
                        "licence ${current.licenceId} expired on ${current.expiresAt}"
                    } else {
                        "licence ${current.licenceId} is valid until ${current.expiresAt}"
                    },
                )
                InstallOutcome.Installed(stored)
            }
        }
    }

    /** The latest installed licence, or null when none is installed. */
    fun latest(): InstalledLicence? = platform.bindPlatform(actor) { tx.inTransaction { store.latest() } }

    private fun endOf(term: String): LocalDate? {
        val parts = term.split("/")
        if (parts.size != 2) return null
        return runCatching {
            LocalDate.parse(parts[0])
            LocalDate.parse(parts[1])
        }.getOrNull()
    }
}
