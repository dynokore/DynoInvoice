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
     * INV-T-9864 appends two (INV-Q-9779 A), INV-T-11439 appends one (INV-Q-11433 A), INV-T-11910 appends two (INV-Q-11901 A), INV-T-12840 appends one (INV-Q-12767 A), INV-T-13356 appends one (INV-Q-13348), INV-T-13835 appends one (INV-Q-13818), INV-T-16106 appends one (INV-Q-10127 A), INV-T-16683 appends one (INV-Q-16671 A), INV-T-17222 appends three (INV-Q-17215 A), INV-T-17503 appends one (INV-Q-16389), INV-T-15460 appends three (INV-Q-15430 A), INV-T-16220 appends one (INV-Q-16217 A), INV-T-16273 appends two (INV-Q-16272 A).
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
            "Purge audit entries",
            "Request an anonymised production sample copy",
            "Approve an anonymised production sample copy",
            "View coverage, deadline and correction metrics per entity and period",
            "Approve a backfill reconciliation before transmission",
            "Defer a document or group in the exception queue",
            "Request elevation",
            "Invite operator diagnostics, time-boxed",
            "Reseal a break-glass session",
            "Open a tenant-scoped operator session",
            "Release a document held for manual review",
            "Request an implementation consultant grant",
            "View the exception queue",
            "Record an IRAS-granted filing extension",
            "Gap report and progress",
            "Export the gap report for round-trip remediation",
            "Import counterparties: dry run, then apply",
            "Record a sandbox certification result",
            "View the accreditation record and its currency",
            "List the re-accreditation assessment tasks",
            "View a re-accreditation assessment task",
            "Reissue the first administrator invitation",
            "View a licence",
            "View a tenant provisioning request",
            "Record onboarding questionnaire answers",
            "Preview the due dates a staged calendar pack would move",
            "Import a rate file",
            "Enter a missing rate with evidence",
            "Configure an external connection record",
            "Connection registry and health: Access Point, Check GST Register, directory, rate sources",
            "Test a connection",
            "Rotate credentials with an overlap window",
        )

    /** The (key, row) pairs of the two enquiry lookups and the rendition sample check (INV-T-10492), compared by equality. */
    val lookupSamplePairs: Map<String, String> =
        mapOf(
            "enquiry-lookup.by-acknowledgement" to "Look up an enquiry by Acknowledgement ID",
            "enquiry-lookup.reverse" to "Reverse-look up an enquiry",
            "rendition-sample.record" to "Record a rendition sample check",
        )

    /** The (key, row) pairs of the two sample copy keys (INV-T-11910, INV-Q-11901 A), compared by equality. */
    val sampleCopyPairs: Map<String, String> =
        mapOf(
            "sample-copy.request" to "Request an anonymised production sample copy",
            "sample-copy.approve" to "Approve an anonymised production sample copy",
        )

    /** The (key, row) pair of the compliance metrics key (INV-T-12840, INV-Q-12767 A), compared by equality. */
    val complianceMetricsPairs: Map<String, String> =
        mapOf("compliance-metrics.view" to "View coverage, deadline and correction metrics per entity and period")

    /** The (key, row) pair of the backfill approval key (INV-T-13356, INV-Q-13348), compared by equality. */
    val backfillPairs: Map<String, String> =
        mapOf("backfill.approve" to "Approve a backfill reconciliation before transmission")

    /** The (key, row) pair of the exception deferral key (INV-T-13835, INV-Q-13818), compared by equality. */
    val deferPairs: Map<String, String> =
        mapOf("exception.defer" to "Defer a document or group in the exception queue")

    /** The (key, row) pairs of the operator elevation request and diagnostic invitation keys (INV-T-15959, INV-Q-15950 A), compared by equality. */
    val elevationRequestPairs: Map<String, String> =
        mapOf(
            "elevation.request" to "Request elevation",
            "diagnostic.invite" to "Invite operator diagnostics, time-boxed",
        )

    /** The (key, row) pair of the operator session key (INV-T-16477, INV-Q-16469 A), compared by equality. */
    val operatorSessionPairs: Map<String, String> =
        mapOf("operator-session.open" to "Open a tenant-scoped operator session")

    /** The (key, row) pair of the consultant grant request key (INV-T-15797, INV-Q-15794 A), compared by equality. */
    val consultantGrantPairs: Map<String, String> =
        mapOf("consultant-grant.request" to "Request an implementation consultant grant")

    /** The (key, row) pair of the held-document release key (INV-T-16540, INV-Q-16154 A), compared by equality. */
    val heldDocumentPairs: Map<String, String> =
        mapOf("held-document.release" to "Release a document held for manual review")

    /** The (key, row) pair of the period extension record key (INV-T-16683, INV-Q-16671 A), compared by equality. */
    val periodExtensionPairs: Map<String, String> =
        mapOf("period-extension.record" to "Record an IRAS-granted filing extension")

    /** The (key, row) pair of the sandbox certification record key (INV-T-16406, INV-Q-16389), compared by equality. */
    val sandboxCertificationPairs: Map<String, String> =
        mapOf("sandbox-certification.record" to "Record a sandbox certification result")

    /** The (key, row) pairs of the gap report and counterparty import keys (INV-T-17222, INV-Q-17215 A; text of record INV-NOTE-7 SCR-19.2, SCR-19.4, SCR-19.3), compared by equality. */
    val gapReportPairs: Map<String, String> =
        mapOf(
            "gap-report.view" to "Gap report and progress",
            "gap-report.export" to "Export the gap report for round-trip remediation",
            "counterparty-import.run" to "Import counterparties: dry run, then apply",
        )

    /** The (key, row) pairs of the accreditation read keys (INV-T-15460, INV-Q-15430 A), compared by equality. */
    val accreditationReadPairs: Map<String, String> =
        mapOf(
            "accreditation.view" to "View the accreditation record and its currency",
            "accreditation-task.list" to "List the re-accreditation assessment tasks",
            "accreditation-task.view" to "View a re-accreditation assessment task",
        )

    /** The (key, row) pair of the first administrator invitation reissue key (INV-T-16220, INV-Q-16217 A), compared by equality. */
    val invitationReissuePairs: Map<String, String> =
        mapOf("tenant-invitation.reissue" to "Reissue the first administrator invitation")

    /** The (key, row) pairs of the licence and tenant provisioning request read keys (INV-T-16273, INV-Q-16272 A), compared by equality. */
    val commercialReadPairs: Map<String, String> =
        mapOf(
            "licence.view" to "View a licence",
            "tenant-provisioning-request.view" to "View a tenant provisioning request",
        )

    /** The (key, row) pair of the onboarding questionnaire answer key (INV-T-18097, coordinator ruling option A; the label is own wording, Inference), compared by equality. */
    val questionnairePairs: Map<String, String> =
        mapOf("questionnaire.answer" to "Record onboarding questionnaire answers")

    /** The (key, row) pair of the calendar pack delta read key (INV-T-16826, INV-Q-16810 C; key, label and holder are Inference), compared by equality. */
    val calendarPackDeltaPairs: Map<String, String> =
        mapOf("calendar-pack-delta.view" to "Preview the due dates a staged calendar pack would move")

    /** The (key, row) pairs of the seven connection and exchange-rate keys (INV-T-18769, ruling on the answered design question of INV-T-4947; connection.configure and its label are Inference), compared by equality. */
    val connectionPairs: Map<String, String> =
        mapOf(
            "exchange-rate.import" to "Import a rate file",
            "exchange-rate.enter" to "Enter a missing rate with evidence",
            "connection.configure" to "Configure an external connection record",
            "connection.view" to "Connection registry and health: Access Point, Check GST Register, directory, rate sources",
            "connection.test" to "Test a connection",
            "connection-credential.rotate" to "Rotate credentials with an overlap window",
            "connection-health.view" to "Connection registry and health: Access Point, Check GST Register, directory, rate sources",
        )

    /** The (key, row) pair of the exception queue read key (INV-T-16573, INV-Q-15877 A), compared by equality. */
    val exceptionViewPairs: Map<String, String> =
        mapOf("exception.view" to "View the exception queue")

    /** The (key, row) pair of the break-glass reseal key (INV-T-16106, INV-Q-10127 A), compared by equality. */
    val resealPairs: Map<String, String> =
        mapOf("break-glass.reseal" to "Reseal a break-glass session")

    /** Rows whose keys are a closed set (INV-T-10149): any key on them, or named like them, that is not in [required] is reported. */
    private val closedRows: Set<String> = setOf(
            "Acknowledge a control relaxation",
            "Run the authority incarnation command",
            "Record the filing fact (confirmFiling), upload the filed return, run and annotate reconciliation, attest as preparer",
        )

    private val closedKeyPattern = Regex("^vp-[0-9]+\\.acknowledge|^authority-incarnation\\.|^filed-return\\.")

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
            "filed-return.upload" to
                Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver") + cells(withinScope, both, "agent.operator")),
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
            "accreditation.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "release.manager")),
            "accreditation-task.list" to Required(PermissionKind.READ, cells(permitted, saasForm, "release.manager")),
            "accreditation-task.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "release.manager")),
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
            "election-change.withdraw" to
                Required(PermissionKind.WRITE, cells(permitted, both, "entity.admin", "tenant.admin", "tax.approver")),
            "election-change.list" to
                Required(PermissionKind.READ, cells(permitted, both, "entity.admin", "tenant.admin", "tax.approver")),
            "enquiry-lookup.by-acknowledgement" to
                Required(PermissionKind.READ, cells(permitted, both, "auditor", "tax.approver", "tax.operator")),
            "enquiry-lookup.reverse" to Required(PermissionKind.READ, cells(permitted, both, "auditor", "tax.approver")),
            "rendition-sample.record" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.operator")),
            "audit-entry.purge" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.engineer")),
            "security-case.record" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "sample-copy.request" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "sample-copy.approve" to Required(PermissionKind.WRITE, cells(permitted, both, "security.officer")),
            "compliance-metrics.view" to Required(PermissionKind.READ, cells(permitted, both, "tax.approver", "finance.viewer")),
            "backfill.approve" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.approver")),
            "exception.defer" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.operator") + cells(withinScope, both, "agent.operator")),
            "elevation.request" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "platform.support")),
            "diagnostic.invite" to Required(PermissionKind.WRITE, cells(permitted, privateForm, "deployment.operator")),
            "tenant-invitation.reissue" to Required(PermissionKind.WRITE, cells(permitted, saasForm, "commercial.admin")),
            "licence.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "commercial.admin")),
            "tenant-provisioning-request.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "commercial.admin")),
            "questionnaire.answer" to Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin")),
            "calendar-pack-delta.view" to Required(PermissionKind.READ, cells(permitted, saasForm, "tax.approver") + cells(permitted, privateForm, "tax.approver")),
            "exchange-rate.import" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.operator") + cells(permitted, privateForm, "deployment.operator")),
            "exchange-rate.enter" to Required(PermissionKind.WRITE, cells(permitted, both, "tax.operator")),
            "connection.configure" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "connection.view" to
                Required(
                    PermissionKind.READ,
                    cells(permitted, both, "integration.engineer") + cells(permitted, both, "tenant.admin") + cells(permitted, privateForm, "deployment.operator"),
                ),
            "connection.test" to Required(PermissionKind.READ, cells(permitted, both, "integration.engineer") + cells(permitted, privateForm, "deployment.operator")),
            "connection-credential.rotate" to
                Required(PermissionKind.WRITE, cells(permitted, both, "tenant.admin") + cells(permitted, saasForm, "platform.engineer")),
            "connection-health.view" to
                Required(
                    PermissionKind.READ,
                    cells(permitted, both, "integration.engineer") + cells(permitted, saasForm, "platform.engineer") + cells(permitted, privateForm, "deployment.operator"),
                ),
            "break-glass.reseal" to Required(PermissionKind.WRITE, cells(permitted, privateForm, "deployment.operator")),
            "operator-session.open" to
                Required(
                    PermissionKind.WRITE,
                    cells(permitted, saasForm, "platform.support", "platform.engineer") + cells(permitted, privateForm, "deployment.operator"),
                ),
            "held-document.release" to
                Required(
                    PermissionKind.WRITE,
                    cells(permitted, saasForm, "tax.approver") + cells(permitted, privateForm, "tax.approver"),
                ),
            "period-extension.record" to
                Required(
                    PermissionKind.WRITE,
                    cells(permitted, saasForm, "tax.approver") + cells(permitted, privateForm, "tax.approver"),
                ),
            "sandbox-certification.record" to Required(PermissionKind.WRITE, cells(permitted, both, "integration.engineer")),
            "gap-report.view" to
                Required(
                    PermissionKind.READ,
                    cells(permitted, both, "tax.operator", "tax.approver"),
                ),
            "gap-report.export" to Required(PermissionKind.READ, cells(permitted, both, "tax.operator")),
            "counterparty-import.run" to
                Required(
                    PermissionKind.WRITE,
                    cells(permitted, both, "tax.operator", "implementation.consultant"),
                ),
            "consultant-grant.request" to
                Required(
                    PermissionKind.WRITE,
                    cells(permitted, saasForm, "platform.support") + cells(permitted, privateForm, "deployment.operator"),
                ),
            "exception.view" to
                Required(
                    PermissionKind.READ,
                    cells(permitted, both, "tenant.admin", "tax.operator", "tax.approver") + cells(withinScope, both, "agent.operator"),
                ),
        )

    /**
     * The machine section (INV-T-13568, INV-REQ-584): fails closed on a type outside the six (a lowercase or space-padded
     * name is outside), a type of the six with no row, a permission already starting with `machine.` or with blank or
     * padded text, a duplicate (type, permission) row, and a person key starting with `machine.`.
     */
    fun checkMachine(
        table: RolePermissionTable,
        rows: List<MachinePermissionRow>,
    ): List<String> {
        val v = mutableListOf<String>()
        rows.forEach { r ->
            if (r.principalType !in MachinePermissions.types) v += "machine row '${r.principalType}' '${r.permission}': principal type is not one of the six."
            if (r.permission.startsWith(MachinePermissions.PREFIX)) v += "machine row ${r.principalType}: permission '${r.permission}' already starts with 'machine.'."
            if (r.permission.isEmpty() || r.permission != r.permission.trim()) v += "machine row ${r.principalType}: permission '${r.permission}' is empty or padded."
        }
        (MachinePermissions.types - rows.map { it.principalType }.toSet()).forEach { v += "machine type $it: no row." }
        rows.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { v += "machine row ${it.principalType} ${it.permission}: listed more than once." }
        table.permissions.filter { it.key.startsWith(MachinePermissions.PREFIX) }.forEach { v += "person key ${it.key}: starts with 'machine.', which is the machine namespace." }
        return v
    }

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
                v += "key ${p.key}: not one of the keys the criteria require for the acknowledge, incarnation and filing families (INV-T-10149, INV-T-13529)."
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
        sampleCopyPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${sampleCopyPairs.keys.joinToString()}, INV-T-11910)."
        }
        table.permissions.filter { it.key !in sampleCopyPairs && (it.row in sampleCopyPairs.values || it.key.startsWith("sample-copy")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${sampleCopyPairs.keys.joinToString()}, which alone carry the sample copy rows (INV-T-11910)."
        }
        complianceMetricsPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${complianceMetricsPairs.keys.joinToString()}, INV-T-12840)."
        }
        table.permissions.filter { it.key !in complianceMetricsPairs && (it.row in complianceMetricsPairs.values || it.key.startsWith("compliance-metric")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${complianceMetricsPairs.keys.joinToString()}, which alone carry the compliance metrics row (INV-T-12840)."
        }
        backfillPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${backfillPairs.keys.joinToString()}, INV-T-13356)."
        }
        table.permissions.filter { it.key !in backfillPairs && (it.row in backfillPairs.values || it.key.startsWith("backfill")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${backfillPairs.keys.joinToString()}, which alone carry the backfill approval row (INV-T-13356)."
        }
        deferPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${deferPairs.keys.joinToString()}, INV-T-13835)."
        }
        table.permissions.filter { it.key !in deferPairs && (it.row in deferPairs.values || it.key.startsWith("exception.defer")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${deferPairs.keys.joinToString()}, which alone carry the exception deferral row (INV-T-13835)."
        }
        elevationRequestPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${elevationRequestPairs.keys.joinToString()}, INV-T-15959)."
        }
        table.permissions.filter { it.key !in elevationRequestPairs && (it.row in elevationRequestPairs.values || it.key.startsWith("elevation.request") || it.key.startsWith("diagnostic.invite")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${elevationRequestPairs.keys.joinToString()}, which alone carry the elevation request and diagnostic invitation rows (INV-T-15959)."
        }
        operatorSessionPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${operatorSessionPairs.keys.joinToString()}, INV-T-16477)."
        }
        table.permissions.filter { it.key !in operatorSessionPairs && (it.row in operatorSessionPairs.values || it.key.startsWith("operator-session")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${operatorSessionPairs.keys.joinToString()}, which alone carry the operator session row (INV-T-16477)."
        }
        consultantGrantPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${consultantGrantPairs.keys.joinToString()}, INV-T-15797)."
        }
        table.permissions.filter { it.key !in consultantGrantPairs && (it.row in consultantGrantPairs.values || it.key.startsWith("consultant-grant")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${consultantGrantPairs.keys.joinToString()}, which alone carry the consultant grant request row (INV-T-15797)."
        }
        heldDocumentPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${heldDocumentPairs.keys.joinToString()}, INV-T-16540)."
        }
        table.permissions.filter { it.key !in heldDocumentPairs && (it.row in heldDocumentPairs.values || it.key.startsWith("held-document")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${heldDocumentPairs.keys.joinToString()}, which alone carry the held document release row (INV-T-16540)."
        }
        periodExtensionPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${periodExtensionPairs.keys.joinToString()}, INV-T-16683)."
        }
        table.permissions.filter { it.key !in periodExtensionPairs && (it.row in periodExtensionPairs.values || it.key.startsWith("period-extension")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${periodExtensionPairs.keys.joinToString()}, which alone carry the period extension record row (INV-T-16683)."
        }
        sandboxCertificationPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${sandboxCertificationPairs.keys.joinToString()}, INV-T-17503)."
        }
        table.permissions.filter { it.key !in sandboxCertificationPairs && (it.row in sandboxCertificationPairs.values || it.key.startsWith("sandbox-certification")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${sandboxCertificationPairs.keys.joinToString()}, which alone carry the sandbox certification record row (INV-T-17503)."
        }
        gapReportPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${gapReportPairs.keys.joinToString()}, INV-T-17222)."
        }
        table.permissions.filter { it.key !in gapReportPairs && (it.row in gapReportPairs.values || it.key.startsWith("gap-report.") || it.key.startsWith("counterparty-import.")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${gapReportPairs.keys.joinToString()}, which alone carry the gap report and counterparty import rows (INV-T-17222)."
        }
        accreditationReadPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${accreditationReadPairs.keys.joinToString()}, INV-T-15460)."
        }
        table.permissions.filter { it.key !in accreditationReadPairs && (it.row in accreditationReadPairs.values || it.key == "accreditation.view" || it.key.startsWith("accreditation-task.")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${accreditationReadPairs.keys.joinToString()}, which alone carry the accreditation read rows (INV-T-15460)."
        }
        exceptionViewPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${exceptionViewPairs.keys.joinToString()}, INV-T-16573)."
        }
        table.permissions.filter { it.key !in exceptionViewPairs && (it.row in exceptionViewPairs.values || it.key.startsWith("exception.view")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${exceptionViewPairs.keys.joinToString()}, which alone carry the exception queue row (INV-T-16573)."
        }
        invitationReissuePairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${invitationReissuePairs.keys.joinToString()}, INV-T-16220)."
        }
        table.permissions.filter { it.key !in invitationReissuePairs && (it.row in invitationReissuePairs.values || it.key.startsWith("tenant-invitation.")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${invitationReissuePairs.keys.joinToString()}, which alone carry the first administrator invitation reissue row (INV-T-16220)."
        }
        commercialReadPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${commercialReadPairs.keys.joinToString()}, INV-T-16273)."
        }
        table.permissions.filter { it.key !in commercialReadPairs && it.row in commercialReadPairs.values }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${commercialReadPairs.keys.joinToString()}, which alone carry the licence and tenant provisioning request read rows (INV-T-16273)."
        }
        questionnairePairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${questionnairePairs.keys.joinToString()}, INV-T-18097)."
        }
        table.permissions.filter { it.key !in questionnairePairs && (it.row in questionnairePairs.values || it.key.startsWith("questionnaire.")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${questionnairePairs.keys.joinToString()}, which alone carry the onboarding questionnaire answer row (INV-T-18097)."
        }
        calendarPackDeltaPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${calendarPackDeltaPairs.keys.joinToString()}, INV-T-16826)."
        }
        table.permissions.filter { it.key !in calendarPackDeltaPairs && (it.row in calendarPackDeltaPairs.values || it.key.startsWith("calendar-pack-delta")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${calendarPackDeltaPairs.keys.joinToString()}, which alone carry the calendar pack delta preview row (INV-T-16826)."
        }
        connectionPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${connectionPairs.keys.joinToString()}, INV-T-18769)."
        }
        table.permissions.filter { it.key !in connectionPairs && (it.row in connectionPairs.values || it.key.startsWith("connection") || it.key.startsWith("exchange-rate")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${connectionPairs.keys.joinToString()}, which alone carry the connection and exchange-rate rows (INV-T-18769)."
        }
        resealPairs.forEach { (key, row) ->
            val p = table.permissions.firstOrNull { it.key == key }
            if (p != null && p.row != row) v += "key $key: row '${p.row}' differs from '$row' (keys ${resealPairs.keys.joinToString()}, INV-T-16106)."
        }
        table.permissions.filter { it.key !in resealPairs && (it.row in resealPairs.values || it.key.startsWith("break-glass.reseal")) }.forEach { p ->
            v += "key ${p.key}: not one of the keys ${resealPairs.keys.joinToString()}, which alone carry the break-glass reseal row (INV-T-16106)."
        }
        table.permissions.filter { it.key.startsWith("break-glass.") && it.key !in required }.forEach { p ->
            v += "key ${p.key}: not one of the break-glass keys the criteria require (INV-T-16106)."
        }
        table.permissions.filter { it.key in setOf("election-change.approve", "election-change.withdraw", "election-change.list") }.forEach { p ->
            if (p.row != electionChangeRow) v += "key ${p.key}: row '${p.row}' differs from '$electionChangeRow' (INV-T-10123, INV-T-13530)."
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
