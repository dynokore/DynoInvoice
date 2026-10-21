package gst.catalogue

/**
 * Checks `role-permissions.yaml` (INV-T-7987, INV-Q-7979 A) against the text of record: the 19 roles of INV-NOTE-3
 * section 22.2, their deployment forms (INV-REQ-876, INV-REQ-888), the 32 published row labels of section 22.3, its
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

    /** The 32 published row labels: 19 of section 22.3, 11 of its amendment table, 2 added by INV-REQ-999. Copied from Keel. */
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
        )

    /** The two keys that serve two published rows each (INV-Q-8148): the (key, row) pairs, compared by equality. */
    val retentionPairs: Map<String, String> =
        mapOf(
            "retention.delete.request" to "Delete data at end of retention",
            "retention.delete.approve" to "Delete data at end of retention (private form)",
        )

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
        )

    fun check(table: RolePermissionTable): List<String> {
        val v = mutableListOf<String>()
        checkRoles(table, v)
        checkKeysAndRows(table, v)
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
        v: MutableList<String>,
    ) {
        table.permissions.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach { v += "key $it: repeated." }
        table.permissions.forEach { p ->
            if (!keyPattern.matches(p.key)) v += "key ${p.key}: does not match ${keyPattern.pattern}."
            if (p.row !in publishedRows) v += "key ${p.key}: row '${p.row}' is not one of the 32 published labels."
        }
        retentionPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (retention keys ${retentionPairs.keys.joinToString()})."
        }
        val used = table.permissions.map { it.row }.toSet()
        publishedRows.filter { it !in used }.forEach { v += "row '$it': no key transcribes it." }
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
