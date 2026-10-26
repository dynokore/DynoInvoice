package gst.catalogue

/**
 * Checks `role-permissions.yaml` (INV-T-7987, INV-Q-7979 A) against the text of record: the 19 roles of INV-NOTE-3
 * section 22.2, their deployment forms (INV-REQ-876, INV-REQ-888), the 34 published row labels of section 22.3, its
 * amendment table and INV-REQ-999, and the cells the criteria assert. It reads the table only, no network. Every
 * violation names the role, key and form it is about.
 */
object RolePermissionsChecker {
    private val both = DeploymentForm.entries.toSet()
    private val keyPattern = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$")

    /** The 19 role names of section 22.2, each with its forms. */
    val expectedRoleForms: Map<String, Set<DeploymentForm>> =
        mapOf(
            "tenant.admin" to both,
            "tax.operator" to both,
            "tax.approver" to both,
            "finance.viewer" to both,
            "integration.engineer" to both,
            "auditor" to both,
            "agent.operator" to both,
            "platform.support" to setOf(DeploymentForm.SAAS),
            "platform.engineer" to setOf(DeploymentForm.SAAS),
            "regulatory.author" to both,
            "regulatory.approver" to both,
            "entity.admin" to both,
            "security.officer" to both,
            "billing.contact" to both,
            "client.signatory" to both,
            "deployment.operator" to setOf(DeploymentForm.PRIVATE),
            "implementation.consultant" to both,
            "release.manager" to setOf(DeploymentForm.SAAS),
            "commercial.admin" to setOf(DeploymentForm.SAAS),
        )

    /** The 34 published row labels: 19 of section 22.3, 11 of its amendment table, 2 added by INV-REQ-999, 2 of FR-AF.34 and section 31.6 (INV-T-9376). Copied from Keel. */
    val publishedRows: List<String> =
        listOf(
            "View submission status",
            "View invoice content",
            "Ingest transactions via API",
            "Resolve master data exceptions",
            "Author mapping pack",
            "Approve mapping pack",
            "Activate an approved mapping pack",
            "Override a blocking validation",
            "Administer override policy",
            "View period readiness (no drain)",
            "Run period readiness check — drains, irreversible (FR-C.8, §31.2.2)",
            "Replay a failed submission",
            "Configure adapters",
            "Manage AP credentials",
            "Export audit pack",
            "View audit log",
            "Manage users and roles",
            "Activate a regulatory pack",
            "Delete data at end of retention",
            "Manage entity registry, identity triple, elections, issuance mode, trigger waivers, go-live, suspension, deregistration, offboarding",
            "Approve operator elevation or diagnostic invitation; review audit log; run access reviews; handle privacy requests",
            "View usage, plan or licence status",
            "Acknowledge mapping effects; grant or withdraw consent",
            "Install, upgrade, patch; import and verify packs; backups; monitoring; support bundle; adapter failover; replay",
            "Delete data at end of retention (private form)",
            "Configure entity set-up, integration and mapping drafts during onboarding",
            "Certify and publish release trains",
            "Manage plans, entitlements, licences",
            "Unlock break-glass access",
            "Run a job now, pause and resume it (FR-AG.64)",
            "Import and verify a regulatory pack",
            "Arm a regulatory pack",
            "Confirm filing — agents under FILE_GST_RETURN too (FR-AF.34)",
            "Record the filing fact (confirmFiling), upload the filed return, run and annotate reconciliation, attest as preparer",
        )

    /** The published row `election-change.approve` transcribes, the row of `entity-config.manage` (INV-T-10123). */
    const val electionChangeRow: String =
        "Manage entity registry, identity triple, elections, issuance mode, trigger waivers, go-live, suspension, deregistration, offboarding"

    /** The two keys that serve two published rows each (INV-Q-8148): the (key, row) pairs, compared by equality. */
    val retentionPairs: Map<String, String> =
        mapOf(
            "retention.delete.request" to "Delete data at end of retention",
            "retention.delete.approve" to "Delete data at end of retention (private form)",
        )

    /**
     * Labels added to the matrix by INV-NOTE-10136 (INV-Q-10127 A), compared by equality; INV-T-10134 appends six, INV-T-10149 the last two,
     * INV-T-9864 appends two (INV-Q-9779 A).
     */
    val addedRows: List<String> =
        listOf(
            "Set a variation point",
            "Change the filing frequency of an entity",
            "Confirm filing periods of an entity",
            "Withdraw a submission",
            "Record an accreditation",
            "Conclude an accreditation task",
            "Approve a tenant schedule",
            "Acknowledge a control relaxation",
            "Run the authority incarnation command",
            "Author a regulatory pack",
            "Approve a regulatory pack",
            "Look up an enquiry by Acknowledgement ID",
            "Reverse-look up an enquiry",
            "Record a rendition sample check",
        )

    /** The (key, row) pairs of the two enquiry lookups and the rendition sample check (INV-T-10492), compared by equality. */
    val lookupSamplePairs: Map<String, String> =
        mapOf(
            "enquiry-lookup.by-acknowledgement" to "Look up an enquiry by Acknowledgement ID",
            "enquiry-lookup.reverse" to "Reverse-look up an enquiry",
            "rendition-sample.record" to "Record a rendition sample check",
        )

    /** Rows whose keys are a closed set (INV-T-10149): any key on them, or named like them, that is not in [required] is reported. */
    private val closedRows: Set<String> = setOf("Acknowledge a control relaxation", "Run the authority incarnation command")

    private val closedKeyPattern = Regex("^vp-[0-9]+\\.acknowledge|^authority-incarnation\\.")

    /** A required key: its kind and exact cells. */
    data class Required(
        val kind: PermissionKind,
        val cells: Set<CellRow>,
    )

    private fun cells(
        allowance: RoleAllowance,
        forms: Set<DeploymentForm>,
        vararg roles: String,
    ): Set<CellRow> = roles.flatMap { r -> forms.map { CellRow(r, it, allowance) } }.toSet()

    private val permitted = RoleAllowance.PERMITTED
    private val withinScope = RoleAllowance.WITHIN_SCOPE
    private val saasForm = setOf(DeploymentForm.SAAS)
    private val privateForm = setOf(DeploymentForm.PRIVATE)

    /** The cells the criteria assert, per key; the check fails on a cell missing, extra or different. */
    val required: Map<String, Required> =
        mapOf(
            "user.manage" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "ap-credential.manage" to
                Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin") + cells(permitted, saasForm, "platform.engineer")),
            "entity-config.manage" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "entity-config.confirm" to
                Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin", "tenant.admin", "tax.approver")),
            "doc.view.content" to
                Required(
                    PermissionKind.READ,
                    cells(permitted, both, "tenant.admin", "tax.operator", "tax.approver", "finance.viewer", "auditor") +
                        cells(RoleAllowance.WITHIN_SCOPE, both, "agent.operator"),
                ),
            "support-bundle.export" to Required(PermissionKind.READ, cells(permitted, privateForm, "deployment.operator")),
            "regulatory-pack.import-verify" to
                Required(PermissionKind.WRITE, cells(permitted, privateForm, "deployment.operator", "tax.approver")),
            "regulatory-pack.arm" to Required(PermissionKind.WRITE, cells(permitted, privateForm, "tax.approver")),
            "regulatory-pack.author" to Required(PermissionKind.WRITE, cells(permitted, both, "regulatory.author")),
            "regulatory-pack.approve" to Required(PermissionKind.WRITE, cells(permitted, both, "regulatory.approver")),
            "regulatory-pack.activate" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer")),
            "retention.delete.request" to
                Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "retention.delete.approve" to
                Required(PermissionKind.WRITE, cells(permitted, saasForm, "security.officer") + cells(permitted, privateForm, "tax.approver")),
            "elevation.decide" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "access-review.run" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "privacy-request.handle" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "audit-log.view" to
                Required(
                    PermissionKind.READ,
                    cells(permitted, both, "tenant.admin", "tax.approver", "auditor", "security.officer") +
                        cells(permitted, saasForm, "platform.engineer"),
                ),
            "mapping-effect.acknowledge" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "consent.grant" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "consent.withdraw" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "break-glass.unlock" to
                Required(
                    PermissionKind.WRITE,
                    cells(permitted, privateForm, "deployment.operator") + cells(permitted, both, "tenant.admin", "tax.approver"),
                ),
            "onboarding.entity-setup.configure" to Required(PermissionKind.WRITE, cells(permitted, both, "implementation.consultant")),
            "onboarding.integration.configure" to Required(PermissionKind.WRITE, cells(permitted, both, "implementation.consultant")),
            "onboarding.mapping-draft.configure" to Required(PermissionKind.WRITE, cells(permitted, both, "implementation.consultant")),
            "usage.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "billing.contact", "tenant.admin")),
            "plan.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "billing.contact", "tenant.admin")),
            "licence-status.view" to Required(PermissionKind.READ, cells(permitted, privateForm, "billing.contact", "tenant.admin")),
            "job.operate" to
                Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "job.operate-whole-job" to
                Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "filing.confirm" to
                Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver") + cells(withinScope, both, "agent.operator")),
            "filed-return.upload" to Required(PermissionKind.WRITE, cells(withinScope, both, "agent.operator")),
            "reconciliation.run" to Required(PermissionKind.WRITE, cells(withinScope, both, "agent.operator")),
            "reconciliation.annotate" to Required(PermissionKind.WRITE, cells(withinScope, both, "agent.operator")),
            "reconciliation.attest" to Required(PermissionKind.WRITE, cells(withinScope, both, "agent.operator")),
            "vp-01.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-02.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-03.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-04.set" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-05.set" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "vp-06.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-07.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-08.set" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "vp-09.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-10.set" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-11.set" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-12.set" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-13.set" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-14.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-15.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-16.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-17.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-18.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-19.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-20.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-21.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-22.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-23.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-24.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-25.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-26.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-27.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-28.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-29.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-30.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.operator")),
            "vp-31.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-32.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-33.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-34.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-35.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-36.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-37.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-38.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-39.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-40.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-41.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-42.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-43.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-44.set" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "vp-45.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "commercial.admin")),
            "vp-46.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-47.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-48.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-49.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-50.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-51.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-52.set" to Required(PermissionKind.WRITE, cells(permitted, privateForm, "deployment.operator")),
            "vp-53.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-54.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-55.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-56.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-57.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-58.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-59.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-60.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-61.set" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-62.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-63.set" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "vp-64.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-65.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-66.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-67.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-68.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-69.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-70.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-71.set" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "vp-72.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-73.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-74.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-75.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-76.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-77.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-78.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-79.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-80.set" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "vp-81.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-82.set" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "filing-frequency.change" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "period.confirm" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin", "tax.approver")),
            "submission.withdraw" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "accreditation.record" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "release.manager")),
            "accreditation.conclude" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "release.manager")),
            "tenant-schedule.approve" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "commercial.admin")),
            "vp-03.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-07.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-40.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-44.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-81.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "vp-13.acknowledge" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-50.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-61.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin")),
            "vp-66.acknowledge" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "security.officer") + cells(permitted, privateForm, "tax.approver")),
            "vp-59.acknowledge.tax-approver" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-59.acknowledge.client-signatory" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "vp-62.acknowledge.tax-approver" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "vp-62.acknowledge.client-signatory" to Required(PermissionKind.WRITE, cells(withinScope, both, "client.signatory")),
            "authority-incarnation.bump" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "election-change.approve" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "enquiry-lookup.by-acknowledgement" to
                Required(PermissionKind.READ, cells(permitted, both, "auditor", "tax.approver", "tax.operator")),
            "enquiry-lookup.reverse" to Required(PermissionKind.READ, cells(permitted, both, "auditor", "tax.approver")),
            "rendition-sample.record" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.operator")),
        )

    fun check(
        table: RolePermissionTable,
        added: List<String> = addedRows,
    ): List<String> {
        val v = mutableListOf<String>()
        checkRoles(table, v)
        checkKeysAndRows(table, added, v)
        checkCells(table, v)
        checkRequired(table, v)
        return v
    }

    private fun checkRoles(
        table: RolePermissionTable,
        v: MutableList<String>,
    ) {
        val names = table.roles.map { it.key }
        names.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { v += "role $it: listed more than once." }
        (expectedRoleForms.keys - names.toSet()).forEach { v += "role $it: missing; section 22.2 has it." }
        (names.toSet() - expectedRoleForms.keys).forEach { v += "role $it: not one of the 19 roles of section 22.2." }
        table.roles.forEach { r ->
            val expected = expectedRoleForms[r.key] ?: return@forEach
            if (r.forms.toSet() != expected || r.forms.size != r.forms.toSet().size) {
                v += "role ${r.key}: forms ${r.forms.joinToString(prefix = "[", postfix = "]")} differ from " +
                    expected.joinToString(prefix = "[", postfix = "]") + "."
            }
        }
    }

    private fun checkKeysAndRows(
        table: RolePermissionTable,
        added: List<String>,
        v: MutableList<String>,
    ) {
        table.permissions.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach { v += "key $it: repeated." }
        table.permissions.forEach { p ->
            if (!keyPattern.matches(p.key)) v += "key ${p.key}: does not match ${keyPattern.pattern}."
            if (p.row !in publishedRows && p.row !in added) {
                v += "key ${p.key}: row '${p.row}' is neither one of the 34 published labels nor one of the added labels."
            }
        }
        table.permissions.forEach { p ->
            val closedRow = p.row in closedRows
            val closedName = closedKeyPattern.containsMatchIn(p.key)
            if ((closedRow || closedName) && p.key !in required) {
                v += "key ${p.key}: not one of the keys the criteria require for the acknowledge and incarnation families (INV-T-10149)."
            }
        }
        retentionPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (retention keys ${retentionPairs.keys.joinToString()})."
        }
        lookupSamplePairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${lookupSamplePairs.keys.joinToString()}, INV-T-10492)."
        }
        table.permissions.filter { it.key !in lookupSamplePairs && it.row in lookupSamplePairs.values }.forEach { p ->
            v += "key ${p.key}: row '${p.row}' belongs to the keys ${lookupSamplePairs.keys.joinToString()} (INV-T-10492)."
        }
        table.permissions.firstOrNull { it.key == "election-change.approve" }?.let { p ->
            if (p.row != electionChangeRow) v += "key ${p.key}: row '${p.row}' differs from '$electionChangeRow' (INV-T-10123)."
        }
        val used = table.permissions.map { it.row }.toSet()
        (publishedRows + added).filter { it !in used }.forEach { v += "row '$it': no key transcribes it." }
    }

    private fun checkCells(
        table: RolePermissionTable,
        v: MutableList<String>,
    ) {
        val formsOf = table.roles.groupBy { it.key }.mapValues { (_, rs) -> rs.flatMap { it.forms }.toSet() }
        table.permissions.forEach { p ->
            p.cells.groupBy { Triple(it.role, it.form, p.key) }.filterValues { it.size > 1 }.keys.forEach { (role, form, key) ->
                v += "role $role key $key form $form: cell listed more than once."
            }
            p.cells.forEach { c ->
                val forms = formsOf[c.role]
                when {
                    forms == null -> v += "role ${c.role} key ${p.key} form ${c.form}: the role is not in the file."
                    c.form !in forms -> v += "role ${c.role} key ${p.key} form ${c.form}: the role does not have that form."
                }
                if (c.role == "auditor" && p.kind == PermissionKind.WRITE) {
                    v += "role auditor key ${p.key} form ${c.form}: an auditor holds no cell on a WRITE key (INV-REQ-863, FR-P.5)."
                }
            }
        }
    }

    private fun checkRequired(
        table: RolePermissionTable,
        v: MutableList<String>,
    ) {
        required.forEach { (key, req) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p == null) {
                v += "key $key: missing; the criteria require it."
                return@forEach
            }
            if (p.kind != req.kind) v += "key $key: kind ${p.kind} differs from ${req.kind}."
            val have = p.cells.toSet()
            (req.cells - have).forEach { c ->
                val other = have.firstOrNull { it.role == c.role && it.form == c.form }
                v += if (other == null) {
                    "role ${c.role} key $key form ${c.form}: required cell ${c.allowance} is missing."
                } else {
                    "role ${c.role} key $key form ${c.form}: cell is ${other.allowance}, required ${c.allowance}."
                }
            }
            (have - req.cells).filter { h -> req.cells.none { it.role == h.role && it.form == h.form } }.forEach { c ->
                v += "role ${c.role} key $key form ${c.form}: extra cell ${c.allowance} is not in the required table."
            }
        }
    }
}
