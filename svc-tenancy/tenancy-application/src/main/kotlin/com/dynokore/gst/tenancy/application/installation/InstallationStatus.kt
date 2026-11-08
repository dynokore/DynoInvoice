package com.dynokore.gst.tenancy.application.installation

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant

/** The platform an installation elects at qualification (VP-52, INV-REQ-1313). */
enum class Platform { CUSTOMER_KUBERNETES, SUPPLIED_APPLIANCE }

data class PlatformElection(
    val platform: Platform,
    val electedAt: Instant,
)

sealed interface ElectionOutcome {
    data class Elected(
        val election: PlatformElection,
    ) : ElectionOutcome

    /** The election is not accepted: the SaaS form takes none, and the appliance is deferred (INV-AC-2666). */
    data class Refused(
        val reason: String,
    ) : ElectionOutcome
}

/** electPlatform and getPlatformElection of the installation (INV-REQ-1313, INV-AC-2301, INV-AC-2666). */
class InstallationStatus(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val facts: InstallationFactRepository,
    private val clock: () -> Instant,
    private val saasForm: () -> Boolean,
    private val actor: String,
) {
    /** Records the election of the private installation; the SaaS form and the deferred appliance are refused. */
    fun elect(requested: Platform): ElectionOutcome =
        platform.bindPlatform(actor) {
            tx.inTransaction {
                when {
                    saasForm() -> {
                        ElectionOutcome.Refused("the SaaS form takes no platform election")
                    }

                    requested == Platform.SUPPLIED_APPLIANCE -> {
                        ElectionOutcome.Refused("the supplied appliance is deferred until demand is shown")
                    }

                    else -> {
                        val election = PlatformElection(requested, clock())
                        facts.put(
                            InstallationFact(
                                ELECTION,
                                "${requested.name}$SEPARATOR${election.electedAt}",
                                election.electedAt,
                            ),
                        )
                        ElectionOutcome.Elected(election)
                    }
                }
            }
        }

    /** The election recorded, or null before any election; there is no default. */
    fun election(): PlatformElection? =
        platform.bindPlatform(actor) {
            tx.inTransaction {
                facts.find(ELECTION)?.value?.split(SEPARATOR)?.let {
                    PlatformElection(Platform.valueOf(it[0]), Instant.parse(it[1]))
                }
            }
        }

    companion object {
        const val ELECTION = "platformElection"
        const val SEPARATOR = "\n"
    }
}
