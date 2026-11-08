package com.dynokore.gst.tenancy.application.installation

import com.dynokore.gst.tenancy.application.certification.ReleaseTrainChanged
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant

/** The deployment-readiness codes this service raises and clears (INV-NOTE-3 §21.2). */
enum class DepCode(
    val code: String,
) {
    DEP_004("DEP-004"),
    DEP_005("DEP-005"),
}

/** Publishes InstallationStatusChanged as a platform event in the caller's transaction (INV-Q-14289 A). */
fun interface InstallationStatusPublisher {
    fun changed(
        depCode: DepCode,
        raised: Boolean,
        cause: String,
    )
}

data class InstalledFacts(
    val trainVersion: String,
    val supportedTrainVersions: List<String>,
)

/**
 * recordInstallationFacts and the DEP-004 / DEP-005 state of the installation (INV-REQ-1095, INV-REQ-1076,
 * INV-Q-9823 A).
 * A raise or a clearing is recorded once and published once; a warning never blocks anything.
 */
@Suppress("LongParameterList") // one collaborator per port the state change touches (store, publisher, hook, clock)
class InstallationHealth(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val facts: InstallationFactRepository,
    private val publisher: InstallationStatusPublisher,
    private val trainChanged: ReleaseTrainChanged,
    private val clock: () -> Instant,
    private val actor: String,
) {
    /** Stores the facts (a repeat replaces); raises DEP-004 iff the train is not in the window, by equality. */
    fun record(
        trainVersion: String,
        supportedTrainVersions: List<String>,
    ): InstalledFacts =
        platform.bindPlatform(actor) {
            val previous = tx.inTransaction { read() }
            tx.inTransaction {
                val now = clock()
                facts.put(InstallationFact(TRAIN, trainVersion, now))
                val window = supportedTrainVersions.joinToString(SEPARATOR).ifEmpty { EMPTY }
                facts.put(InstallationFact(WINDOW, window, now))
                val outside = trainVersion !in supportedTrainVersions
                val position = if (outside) "outside" else "in"
                setState(DepCode.DEP_004, outside, "trainVersion $trainVersion is $position the supported window")
                // inside the fact write transaction: a transactional outbox binding of the hook needs it (ARC-21)
                if (previous != null && previous.trainVersion != trainVersion) trainChanged.changed()
            }
            InstalledFacts(trainVersion, supportedTrainVersions)
        }

    /** Raises or clears [code] inside the caller's platform-bound transaction; publishes only on a change. */
    fun setState(
        code: DepCode,
        raised: Boolean,
        cause: String,
    ) {
        val key = STATE_PREFIX + code.code
        val wasRaised = facts.find(key)?.value == RAISED
        if (raised == wasRaised) return // an equal repeat, or nothing to clear
        facts.put(InstallationFact(key, if (raised) RAISED else CLEARED, clock()))
        publisher.changed(code, raised, cause)
    }

    private fun read(): InstalledFacts? {
        val train = facts.find(TRAIN)?.value ?: return null
        val window =
            facts
                .find(WINDOW)
                ?.value
                ?.takeUnless { it == EMPTY }
                ?.split(SEPARATOR)
                .orEmpty()
        return InstalledFacts(train, window)
    }

    companion object {
        const val TRAIN = "trainVersion"
        const val WINDOW = "supportedTrainVersions"
        const val STATE_PREFIX = "depState."
        const val RAISED = "RAISED"
        const val CLEARED = "CLEARED"
        private const val SEPARATOR = "\n"
        private const val EMPTY = "-"
    }
}
