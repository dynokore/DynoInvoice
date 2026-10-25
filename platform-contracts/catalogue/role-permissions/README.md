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

The filing operations of the `FILE_GST_RETURN` scope item (FR-AF.34, INV-T-9376, INV-Q-8866 A) have five keys and two published labels. `filing.confirm` carries the label "Confirm filing — agents under FILE_GST_RETURN too (FR-AF.34)" (INV-NOTE-3 section 31.6): `tax.approver` PERMITTED, `agent.operator` WITHIN_SCOPE. `filed-return.upload`, `reconciliation.run`, `reconciliation.annotate` and `reconciliation.attest` each carry the label "Record the filing fact (confirmFiling), upload the filed return, run and annotate reconciliation, attest as preparer" (the FR-AF.34 line) with `agent.operator` WITHIN_SCOPE and no other cell. A cell for `client.signatory` on `filing.confirm` is not written (INV-Q-9374).

A `break-glass.unlock` cell means that the role may be one of the two persons who unlock. The pair rule (FR-AF.18, enforced by the `unlockBreakGlass` operation of access-svc and by the two-person rule of INV-NOTE-3 section 22.4) is not a catalogue field.

The time-box and the customer approval of an `implementation.consultant` grant (FR-AF.30) are conditions of the grant, not cells.

`client.signatory` cells are WITHIN_SCOPE because the text says "for its own client entity only".

Usage and plan are SAAS (`usage.view`, `plan.view`) and licence status is PRIVATE (`licence-status.view`) (FR-AF.29).

The variation-point setting keys (INV-T-10132, INV-Q-10127 A) are the family `vp-NN.set`, NN the two digits of `VP-NN`, `vp-01.set` to `vp-82.set` (`vp-82.set` was added by INV-T-10283, INV-Q-10280 A), all WRITE with the row label "Set a variation point" (INV-NOTE-10136). The holders of each key equal the `set_by` of its variation point on the day the task merged; `client.signatory` cells are WITHIN_SCOPE, every other cell PERMITTED, in every form the role exists in.

The six operation keys (INV-T-10134, INV-Q-10127 A) are all WRITE: `filing-frequency.change` (row "Change the filing frequency of an entity"; entity.admin), `period.confirm` ("Confirm filing periods of an entity"; entity.admin, tax.approver), `submission.withdraw` ("Withdraw a submission"; tax.approver), `accreditation.record` ("Record an accreditation"; release.manager, SAAS), `accreditation.conclude` ("Conclude an accreditation task"; release.manager, SAAS) and `tenant-schedule.approve` ("Approve a tenant schedule"; commercial.admin, SAAS). Roles with no form named hold the cell in both forms.

The fourteen keys of INV-T-10149 (INV-Q-10140 C, INV-Q-10142 A) are all WRITE. Thirteen carry the row "Acknowledge a control relaxation", one key per accountable role so that VP-59 and VP-62 stay two distinct persons: `vp-03.acknowledge`, `vp-07.acknowledge`, `vp-40.acknowledge`, `vp-44.acknowledge`, `vp-81.acknowledge` (tenant.admin), `vp-13.acknowledge` (client.signatory, WITHIN_SCOPE), `vp-50.acknowledge` (tax.approver), `vp-61.acknowledge` (entity.admin), `vp-66.acknowledge` (security.officer in SAAS, tax.approver in PRIVATE), `vp-59.acknowledge.tax-approver` and `vp-62.acknowledge.tax-approver` (tax.approver), `vp-59.acknowledge.client-signatory` and `vp-62.acknowledge.client-signatory` (client.signatory, WITHIN_SCOPE). The fourteenth, `authority-incarnation.bump`, has the row "Run the authority incarnation command" (platform.engineer in SAAS, deployment.operator in PRIVATE).

The whole-job run and pause are the key `job.operate-whole-job` (INV-REQ-1138), with the `row` of `job.operate`; `job.operate` has no cell for `platform.engineer`.

## What the build checks (`checkRolePermissions`, wired into `check`; also run by `generateRolePermissionCatalogue`)

`RolePermissionsChecker` reports: a role set that differs from the 19 names; a role whose forms differ; a key that
breaks the pattern or repeats; a `row` that is neither one of the 34 published labels nor one of the added labels of `addedRows` (the nine labels of section 2 of INV-NOTE-10136; the text of record of the added labels is INV-NOTE-10136), or a label of either list with no key; a cell that names
a role not in the file or a form the role does not have; a repeated (role, form, key) cell; an `auditor` cell on a
WRITE key (INV-REQ-863, FR-P.5); a (key, row) pair of the two retention keys that differs from the pinned table; and any required cell that is missing, extra or different (the table in the checker).

## Changing it

A new key is a minor step of `gstCatalogueVersion` in `../gradle.properties`, with a comment line naming the task and
ruling. A changed cell of an existing key needs a Keel ruling first; the reviewer compares the cells with INV-NOTE-3.
