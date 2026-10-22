package com.dynokore.gst.access.domain.principal

/**
 * The fixed permission set of each of the six principal types (INV-REQ-584 behaviour 1; INV-Q-8779 A). The keys are
 * those of the data file `access/schemas/machine-principal-permissions.tsv` of the contract task INV-T-8807. A set is
 * built from (type, permission) pairs and is refused when a type has no pair (the start-up stops), so a type can never
 * be issued without a permission set.
 */
class MachinePermissionSets private constructor(
    private val sets: Map<PrincipalType, Set<String>>,
) {
    fun permissionsOf(type: PrincipalType): Set<String> = sets.getValue(type)

    /** The pairs, for comparison with the contract's data file. */
    fun pairs(): Set<Pair<PrincipalType, String>> = sets.flatMap { (type, keys) -> keys.map { type to it } }.toSet()

    /** Exact match of the permission key: no prefix, no case folding. */
    fun permits(
        type: PrincipalType,
        permission: String,
    ): Boolean = permission in sets.getValue(type)

    companion object {
        fun of(pairs: Collection<Pair<PrincipalType, String>>): MachinePermissionSets {
            val grouped = pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
            PrincipalType.entries.forEach {
                require(!grouped[it].isNullOrEmpty()) { "the principal type $it has no permission set" }
            }
            return MachinePermissionSets(grouped)
        }

        /** The service's own set; a start-up check compares it with the rows it is given. */
        val STANDARD: MachinePermissionSets =
            of(
                listOf(
                    PrincipalType.INGEST to "ingestion.channels.write",
                    PrincipalType.INGEST to "control-totals.write",
                    PrincipalType.FILING_WORKFLOW to "readiness.view",
                    PrincipalType.FILING_WORKFLOW to "readiness.run",
                    PrincipalType.FILING_WORKFLOW to "drain.run",
                    PrincipalType.FILING_WORKFLOW to "filing.confirm",
                    PrincipalType.FILING_WORKFLOW to "gst-return-figures.upload",
                    PrincipalType.AP_CALLBACK to "acknowledgements.deliver",
                    PrincipalType.AP_CALLBACK to "delivery-status.deliver",
                    PrincipalType.AP_CALLBACK to "inbound-network-documents.deliver",
                    PrincipalType.REPORTING to "submissions.read",
                    PrincipalType.REPORTING to "status.read",
                    PrincipalType.REPORTING to "reconciliation.read",
                    PrincipalType.MONITORING to "metrics.read",
                    PrincipalType.MONITORING to "health.read",
                    PrincipalType.CERTIFICATION to "certification.ingest",
                    PrincipalType.CERTIFICATION to "certification.results.read",
                    PrincipalType.CERTIFICATION to "certification.report.record",
                    PrincipalType.CERTIFICATION to "certification.status.record",
                ),
            )
    }
}

/** The scope a type may carry (INV-Q-8779 A): MONITORING the installation, the others a tenant or an entity. */
object PrincipalScopes {
    const val INSTALLATION = "installation"

    /** Exact equality: `installation ` with a trailing space, `INSTALLATION` and `tenant:<ref>` are not the literal. */
    fun isInstallation(scope: String): Boolean = scope == INSTALLATION

    /** True when the scope is allowed for [type]; [tenantOrEntity] says whether it parses as `entity:` or `tenant:`. */
    fun accepts(
        type: PrincipalType,
        scope: String,
        tenantOrEntity: Boolean,
    ): Boolean = if (type == PrincipalType.MONITORING) isInstallation(scope) else tenantOrEntity
}
