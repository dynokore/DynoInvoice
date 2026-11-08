# role-permissions

`role-permissions.yaml` (STK-61, INV-T-7987, coordinator ruling INV-Q-7979 A): the role-by-permission table of the
gst-catalogue. It transcribes INV-NOTE-3 section 22.2 (the 19 roles), section 22.3 (the permission matrix and its
amendment table of 24 Sep 2026) and INV-REQ-999 (import, verify and arm a regulatory pack, private form). It is edited
by hand in this repository; there is no vendoring script. `catalogue/generator` is its only reader, and the published
`com.dynokore.gst.catalogue.RolePermissions` serves it (`roles(form)`, `cells(form)`, `kind(permissionKey)`).

## What the file says

- `roles`: `key`, `belongs_to` (the words of section 22.2) and `forms`, the non-empty set of deployment forms (SAAS,
  PRIVATE) the role exists in. `deployment.operator` is PRIVATE only (INV-REQ-876); `platform.support`,
  `platform.engineer`, `release.manager` and `commercial.admin` are SAAS only (INV-REQ-888).
- `permissions`: `key`, `kind` (READ or WRITE), `row` (the label of the published row it transcribes, copied verbatim
  with the markup removed) and `cells`, each {`role`, `form`, `allowance`}. PERMITTED is the published Y,
  WITHIN_SCOPE the published S.
- **A cell that is not listed is DENIED**: the published dash, and every case the published text does not state (fail
  closed). The consent-item condition of the Agent scope paragraph is not a catalogue field (INV-T-4939 owns it).

## Keys

A key is lower-case dotted words, `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$`, for example `user.manage` or
`regulatory-pack.arm`: the object first, then the action. A key is unique, and every function listed in one published
row has its own key, so a row that lists several functions has several keys. A key may serve several rows (for example
`audit-log.view`, `submission.replay`); it names the one row where it is first stated in `row`, and its cells follow
every row that states it. `job.operate` is the key fixed by INV-Q-3782.

Two keys each serve both retention rows (INV-Q-8148): `retention.delete.request` carries the `row` "Delete data at end of retention" and `retention.delete.approve` carries "Delete data at end of retention (private form)"; the checker pins these two (key, row) pairs, and each key's cells follow both rows.

The filing operations of the `FILE_GST_RETURN` scope item (FR-AF.34, INV-T-9376, INV-Q-8866 A) have five keys and two published labels. `filing.confirm` carries the label "Confirm filing — agents under FILE_GST_RETURN too (FR-AF.34)" (INV-NOTE-3 section 31.6): `tax.approver` PERMITTED, `agent.operator` WITHIN_SCOPE. `filed-return.upload`, `reconciliation.run`, `reconciliation.annotate` and `reconciliation.attest` each carry the label "Record the filing fact (confirmFiling), upload the filed return, run and annotate reconciliation, attest as preparer" (the FR-AF.34 line) with `agent.operator` WITHIN_SCOPE. `tax.approver` holds `filed-return.upload` PERMITTED (owner answer INV-Q-10288); the three `reconciliation.*` keys are unchanged until the question for them is answered and have no other cell. A cell for `client.signatory` on `filing.confirm` is not written (INV-Q-9374).

A `break-glass.unlock` cell means that the role may be one of the two persons who unlock. The pair rule (FR-AF.18, enforced by the `unlockBreakGlass` operation of access-svc and by the two-person rule of INV-NOTE-3 section 22.4) is not a catalogue field.

The time-box and the customer approval of an `implementation.consultant` grant (FR-AF.30) are conditions of the grant, not cells.

`client.signatory` cells are WITHIN_SCOPE because the text says "for its own client entity only".

Usage and plan are SAAS (`usage.view`, `plan.view`) and licence status is PRIVATE (`licence-status.view`) (FR-AF.29).

The variation-point setting keys (INV-T-10132, INV-Q-10127 A) are the family `vp-NN.set`, NN the two digits of `VP-NN`, `vp-01.set` to `vp-82.set` (`vp-82.set` was added by INV-T-10283, INV-Q-10280 A), all WRITE with the row label "Set a variation point" (INV-NOTE-10136). The holders of each key equal the `set_by` of its variation point on the day the task merged; `client.signatory` cells are WITHIN_SCOPE, every other cell PERMITTED, in every form the role exists in.

The six operation keys (INV-T-10134, INV-Q-10127 A) are all WRITE: `filing-frequency.change` (row "Change the filing frequency of an entity"; entity.admin), `period.confirm` ("Confirm filing periods of an entity"; entity.admin, tax.approver), `submission.withdraw` ("Withdraw a submission"; tax.approver), `accreditation.record` ("Record an accreditation"; release.manager, SAAS), `accreditation.conclude` ("Conclude an accreditation task"; release.manager, SAAS) and `tenant-schedule.approve` ("Approve a tenant schedule"; commercial.admin, SAAS). Roles with no form named hold the cell in both forms.

The fourteen keys of INV-T-10149 (INV-Q-10140 C, INV-Q-10142 A) are all WRITE. Thirteen carry the row "Acknowledge a control relaxation", one key per accountable role so that VP-59 and VP-62 stay two distinct persons: `vp-03.acknowledge`, `vp-07.acknowledge`, `vp-40.acknowledge`, `vp-44.acknowledge`, `vp-81.acknowledge` (tenant.admin), `vp-13.acknowledge` (client.signatory, WITHIN_SCOPE), `vp-50.acknowledge` (tax.approver), `vp-61.acknowledge` (entity.admin), `vp-66.acknowledge` (security.officer in SAAS, tax.approver in PRIVATE), `vp-59.acknowledge.tax-approver` and `vp-62.acknowledge.tax-approver` (tax.approver), `vp-59.acknowledge.client-signatory` and `vp-62.acknowledge.client-signatory` (client.signatory, WITHIN_SCOPE). The fourteenth, `authority-incarnation.bump`, has the row "Run the authority incarnation command" (platform.engineer in SAAS, deployment.operator in PRIVATE).

The key `election-change.approve` (INV-T-10123, INV-Q-10027) is held by `tax.approver` only and is checked with the person-differs rule of INV-Q-9704 by the service, not by the catalogue.

The keys `election-change.withdraw` (WRITE) and `election-change.list` (READ) (INV-T-13530, INV-Q-13520 A) are held by the same roles as `entity-config.confirm` (`entity.admin`, `tenant.admin`, `tax.approver`), and the person rules (the withdrawer is the requester, the decliner is not) are checked by the service, not by the catalogue; the decline of a change uses `election-change.approve` and has no key of its own.

The keys `migration-cutover.record` (WRITE; `entity.admin`) and `migration-cutover.view` (READ; `entity.admin`, `tenant.admin`, `tax.approver`) (INV-T-18815, INV-Q-10027) carry the row of `entity-config.manage`; the write holder equals that of `entity-config.manage` and the read holders equal those of `election-change.list`, and the keys guard the operations `recordMigrationCutover` and `getMigrationCutover` of tenancy.

The key `enquiry-lookup.by-acknowledgement` (row "Look up an enquiry by Acknowledgement ID") is held by `auditor`, `tax.approver` and `tax.operator`, `enquiry-lookup.reverse` (row "Reverse-look up an enquiry") by `auditor` and `tax.approver`, and `rendition-sample.record` (row "Record a rendition sample check") by `tax.operator` alone, all in SAAS and PRIVATE (INV-T-10492, INV-Q-10378 A, INV-Q-10380 A).

The key `audit-entry.purge` (row "Purge audit entries", WRITE) is held by `platform.engineer` in SAAS only, with no PRIVATE cell because control-plane-svc runs only in the SaaS form (INV-REQ-1155). It was added by INV-T-11439 under INV-Q-11433 A, and its label is the last of `addedRows` (15 labels).

The whole-job run and pause are the key `job.operate-whole-job` (INV-REQ-1138), with the `row` of `job.operate`; `job.operate` has no cell for `platform.engineer`.

## What the build checks (`checkRolePermissions`, wired into `check`; also run by `generateRolePermissionCatalogue`)

`RolePermissionsChecker` reports: a role set that differs from the 19 names; a role whose forms differ; a key that
breaks the pattern or repeats; a `row` that is neither one of the 34 published labels nor one of the added labels of `addedRows` (the twelve labels of section 2 of INV-NOTE-10136 and the two labels "Author a regulatory pack" and "Approve a regulatory pack" of INV-Q-9779 A, and the labels "Request elevation" and "Invite operator diagnostics, time-boxed" of INV-T-15959, and the label "Open a tenant-scoped operator session" of INV-T-16477, and the label "Release a document held for manual review" of INV-T-16540, and the label "Request an implementation consultant grant" of INV-T-15797, and the label "View the exception queue" of INV-T-16573; the text of record of the twelve is INV-NOTE-10136), or a label of either list with no key; a cell that names
a role not in the file or a form the role does not have; a repeated (role, form, key) cell; an `auditor` cell on a
WRITE key (INV-REQ-863, FR-P.5); a (key, row) pair of the two retention keys that differs from the pinned table; and any required cell that is missing, extra or different (the table in the checker).

## Changing it

A new key is a minor step of `gstCatalogueVersion` in `../gradle.properties`, with a comment line naming the task and
ruling. A changed cell of an existing key needs a Keel ruling first; the reviewer compares the cells with INV-NOTE-3.

The keys `regulatory-pack.author` (held by `regulatory.author`) and `regulatory-pack.approve` (held by `regulatory.approver`), both WRITE, PERMITTED in SAAS and PRIVATE, were added by INV-T-9864 under coordinator ruling INV-Q-9779 A; their two row labels are in `addedRows`.

## The operation-to-roles map

The generated `RolePermission.kt` also declares `OperationRoles` (INV-T-10641, INV-NOTE-10639 R3). `OperationRoles.rolesFor(form, permissionKey)` returns the roles with a cell on that key in that form, with each cell's allowance, and `OperationRoles.keys(form)` returns the keys that have at least one cell in that form. The map is generated as a literal from the same yaml file in the same run as `RolePermissions`, in the order of the file. A key that is not in the table, or has no cell in the form, has no roles (an empty map).

The key `security-case.record` (row of `privacy-request.handle`, WRITE) is held by `security.officer` in SAAS and PRIVATE and was added under INV-Q-11686 A.

The keys `sample-copy.request` (row "Request an anonymised production sample copy") and `sample-copy.approve` (row "Approve an anonymised production sample copy"), both WRITE, are held by `tenant.admin` and `security.officer` respectively, alone, in SAAS and PRIVATE. INV-NOTE-10136 is the text of record for both labels (INV-T-11910, INV-Q-11901 A, INV-Q-11899 A).

The key `compliance-metrics.view` (row "View coverage, deadline and correction metrics per entity and period", READ) is held by `tax.approver` and `finance.viewer` alone, PERMITTED in SAAS and PRIVATE; every other role is DENIED. INV-NOTE-10136 (section 2, row 15) is the text of record for the label. It is the scoped permission of the read operation `getComplianceMetrics` (INV-REQ-1069, INV-REQ-1070); the roles are those of the row SCR-06.5 of INV-NOTE-7 (INV-T-12840, owner answer INV-Q-12767 A).

The key `backfill.approve` (row "Approve a backfill reconciliation before transmission", WRITE) is held by `tax.approver` alone, PERMITTED in SAAS and PRIVATE; every other role is DENIED. It is the scoped permission of the approval that INV-REQ-1090 requires before a backfill transmits; that the approver differs from the requester (INV-Q-9704) is a rule the service checks, not a catalogue field (INV-T-13356, owner answer INV-Q-13348).

The key `exception.defer` (row "Defer a document or group in the exception queue", WRITE) is held by `tax.operator` (PERMITTED) and `agent.operator` (WITHIN_SCOPE), in SAAS and PRIVATE; every other role is DENIED, including `tax.approver`. It is the one scoped permission that both `deferDocument` and `undeferDocument` check, so any holder may undo a deferral (INV-REQ-960, INV-T-13826, INV-T-13835, coordinator ruling INV-Q-13818 A, owner answer INV-Q-13802 A).

The key `elevation.request` (row "Request elevation", WRITE) is held by `platform.support` alone, PERMITTED in SAAS only; every other role is DENIED, and the PRIVATE form has no cell. The label is that of F-11.4 quoted in INV-REQ-862. It is the scoped permission of `requestElevation` and `withdrawElevationRequest`; `security.officer` decides through `elevation.decide` (INV-AC-1930, INV-T-15959, coordinator ruling INV-Q-15950 A).

The key `diagnostic.invite` (row "Invite operator diagnostics, time-boxed", WRITE) is held by `deployment.operator` alone, PERMITTED in PRIVATE only; every other role is DENIED, and the SAAS form has no cell. The label is that of SCR-28.3 quoted in INV-REQ-890. It is the scoped permission of `inviteDiagnostics` and `withdrawDiagnosticInvitation`; `security.officer` decides through `elevation.decide` (INV-T-15959, coordinator ruling INV-Q-15950 A).

The key `operator-session.open` (row "Open a tenant-scoped operator session", WRITE) is held by `platform.support` and `platform.engineer`, PERMITTED in SAAS only, and by `deployment.operator`, PERMITTED in PRIVATE only; every other role is DENIED in both forms, and `platform.support` still holds no `doc.view.content` cell (INV-AC-922). It is the scoped permission of `openOperatorSession`, whose published contract names those three roles (INV-REQ-862, INV-T-16477, coordinator ruling INV-Q-16469 A, consumer INV-T-4154).

The key `held-document.release` (row "Release a document held for manual review", WRITE) is held by `tax.approver` alone, PERMITTED in both SAAS and PRIVATE; every other role is DENIED in both forms. It is the scoped permission of `releaseHeldDocument`, which moves a document held with a MANUAL code (ING-017, ING-024) to re-derivation; the same user who triggered the hold's cause may not release it (INV-REQ-1315 behavior 4, INV-T-16540, owner answer INV-Q-16154 A; least privilege and separation of duties, NIST SP 800-53 AC-5, AC-6).

The key `consultant-grant.request` (row "Request an implementation consultant grant", WRITE) is held by `platform.support`, PERMITTED in SAAS only, and by `deployment.operator`, PERMITTED in PRIVATE only; every other role is DENIED in both forms. It is the scoped permission of `requestConsultantGrant`; approval uses the existing key `user.manage` (tenant.admin only) (INV-REQ-908, INV-T-15797, owner answer INV-Q-15794 A).

The key `exception.view` (row "View the exception queue", READ) is held by `tenant.admin`, `tax.operator` and `tax.approver` (PERMITTED) and `agent.operator` (WITHIN_SCOPE), in SAAS and PRIVATE, exactly the holders of `masterdata-exception.resolve`; every other role is DENIED. It is the scoped permission of a person calling `get /v1/exceptions`; the machine type `reporting` keeps `machine.status.read` on the same operation (INV-T-16573, owner ruling INV-Q-15877 A, least privilege NIST SP 800-53 AC-6).

The key `period-extension.record` (row "Record an IRAS-granted filing extension", WRITE) is held by `tax.approver` alone, PERMITTED in both SAAS and PRIVATE; every other role is DENIED in both forms. It is the scoped permission of `recordExtension`, which records an extension granted by IRAS against an entity and period (INV-REQ-86; INV-T-16683, coordinator ruling INV-Q-16671 A; least privilege and separation of duties, NIST SP 800-53 AC-5, AC-6).

The key `break-glass.reseal` (row "Reseal a break-glass session", WRITE) is held by `deployment.operator` alone, PERMITTED in PRIVATE only (the role does not exist in SAAS); every other role is DENIED, including `tenant.admin` and `tax.approver`, which hold `break-glass.unlock`. INV-NOTE-10136 (section 2, row "Reseal a break-glass session") is the text of record for the label. It is the scoped permission of the operation `resealBreakGlass`, whose published contract names `deployment.operator`; nobody gains or loses the right, and who else may reseal is a separate open owner question (INV-T-16106, consumer INV-T-8853, coordinator ruling INV-Q-10127 A).

The keys `gap-report.view` (row "Gap report and progress", READ), `gap-report.export` (row "Export the gap report for round-trip remediation", READ) and `counterparty-import.run` (row "Import counterparties: dry run, then apply", WRITE) are held as follows, PERMITTED in SAAS and PRIVATE; every other role is DENIED, including `tenant.admin`, `agent.operator`, `auditor` and `finance.viewer`: `gap-report.view` by `tax.operator` and `tax.approver`; `gap-report.export` by `tax.operator` alone; `counterparty-import.run` by `tax.operator` and `implementation.consultant`. INV-NOTE-7 (rows SCR-19.2, SCR-19.4 and SCR-19.3) is the text of record for the three labels. They are the scoped permissions of `runGapReport`, the gap export and `importCounterparties` of masterdata-svc (INV-T-17222, coordinator ruling INV-Q-17215 A; least privilege NIST SP 800-53 AC-6, deny by default OWASP ASVS V4.1).

The key `sandbox-certification.record` (row "Record a sandbox certification result", WRITE) is held by `integration.engineer` alone, PERMITTED in both SAAS and PRIVATE; every other role is DENIED in both forms. It is the scoped permission of `certifySandbox`, which records the outcome of a certification kit run (INV-REQ-987; INV-T-17503, coordinator ruling INV-Q-16389; least privilege, NIST SP 800-53 AC-6).

The keys `accreditation.view` (row "View the accreditation record and its currency", READ), `accreditation-task.list` (row "List the re-accreditation assessment tasks", READ) and `accreditation-task.view` (row "View a re-accreditation assessment task", READ) are held by `release.manager` (PERMITTED) in SAAS; every other role is DENIED, and `release.manager` has no PRIVATE cell. They gate the operations `getAccreditation`, `listAccreditationTasks` and `getAccreditationTask` respectively (INV-REQ-1365, INV-T-15460, coordinator ruling INV-Q-15430 A).

The key `tenant-invitation.reissue` (row "Reissue the first administrator invitation", WRITE) is held by `commercial.admin` (PERMITTED) in SAAS; every other role is DENIED, and nobody holds it in PRIVATE. It is the scoped permission of `requestFirstAdministratorInvitationReissue` (INV-REQ-906, INV-T-16220, coordinator ruling INV-Q-16217 A).

The keys `licence.view` (row "View a licence", READ) and `tenant-provisioning-request.view` (row "View a tenant provisioning request", READ) are held by `commercial.admin` (PERMITTED) in SAAS; every other role is DENIED, and nobody holds them in PRIVATE. They are the scoped permissions of `getLicence` and `getTenantProvisioningRequest` respectively (INV-REQ-906, INV-T-16273, coordinator ruling INV-Q-16272 A).

The key `questionnaire.answer` (row "Record onboarding questionnaire answers", WRITE) is held by `tenant.admin` alone, PERMITTED in both SAAS and PRIVATE; every other role is DENIED in both forms, including `entity.admin` and `implementation.consultant`. The label and the key name are own wording (Inference), not a quotation. It is the scoped permission of `answerQuestionnaire` of tenancy-svc, decided per request at tenant scope and at entity scope; the holder is the role of the published contract operation (`tenant.admin` for both scopes), and a wider holder set is an owner matter that no task decides (INV-REQ-1246; INV-T-18097, coordinator ruling option A; least privilege NIST SP 800-53 AC-6, deny by default OWASP ASVS V4.1).

The key `calendar-pack-delta.view` (row "Preview the due dates a staged calendar pack would move", READ) is held by `tax.approver` alone, PERMITTED in both SAAS and PRIVATE; every other role is DENIED in both forms. It is the scoped permission of `getCalendarPackDelta`, the dry-run delta that shows which due dates a staged regulatory pack would move before it is approved and activated; the same role records the extension that moves a due date (`period-extension.record`) and arms a pack in the private form (`regulatory-pack.arm`) (INV-REQ-88; INV-T-16826, owner answer INV-Q-16810 C; the key name, label and holder are Inference; least privilege and separation of duties, NIST SP 800-53 AC-5, AC-6).

The seven keys `exchange-rate.import`, `exchange-rate.enter`, `connection.configure`, `connection.view`, `connection.test`, `connection-credential.rotate` and `connection-health.view` are the scoped permissions of the masterdata operations `importRates`, `enterRate`, `createConnection`, `getConnection`, `testConnection`, `rotateCredential` and `getConnectionHealth`; each is decided per request by `AuthorityReplica.decide`, never by a role name (INV-Q-10027). Every cell not listed is DENIED. `exchange-rate.import` (row "Import a rate file", WRITE) is held by `tax.operator` in SAAS and PRIVATE and by `deployment.operator` in PRIVATE. `exchange-rate.enter` (row "Enter a missing rate with evidence", WRITE) is held by `tax.operator` in SAAS and PRIVATE (INV-REQ-506). `connection.configure` (row "Configure an external connection record", WRITE) is held by `integration.engineer` in SAAS and PRIVATE; the key and label are own wording (Inference) (INV-REQ-834). `connection.view` (row "Connection registry and health: Access Point, Check GST Register, directory, rate sources", READ) is held by `integration.engineer` and `tenant.admin` in SAAS and PRIVATE and by `deployment.operator` in PRIVATE. `connection.test` (row "Test a connection", READ) is held by `integration.engineer` in SAAS and PRIVATE and by `deployment.operator` in PRIVATE (INV-REQ-835). `connection-credential.rotate` (row "Rotate credentials with an overlap window", WRITE) is held by `tenant.admin` in SAAS and PRIVATE and by `platform.engineer` in SAAS (INV-REQ-837). `connection-health.view` (same row label as `connection.view`, READ) is held by `integration.engineer` in SAAS and PRIVATE, by `platform.engineer` in SAAS and by `deployment.operator` in PRIVATE: `integration.engineer` and the operations role of each form (INV-REQ-836). (INV-T-18769, coordinator ruling on the answered design question of INV-T-4947; least privilege NIST SP 800-53 AC-6, deny by default OWASP ASVS V4.1).
