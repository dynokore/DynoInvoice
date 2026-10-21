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

## What the build checks (`checkRolePermissions`, wired into `check`; also run by `generateRolePermissionCatalogue`)

`RolePermissionsChecker` reports: a role set that differs from the 19 names; a role whose forms differ; a key that
breaks the pattern or repeats; a `row` outside the 32 published labels, or one of the 32 with no key; a cell that names
a role not in the file or a form the role does not have; a repeated (role, form, key) cell; an `auditor` cell on a
WRITE key (INV-REQ-863, FR-P.5); and any required cell that is missing, extra or different (the table in the checker).

## Changing it

A new key is a minor step of `gstCatalogueVersion` in `../gradle.properties`, with a comment line naming the task and
ruling. A changed cell of an existing key needs a Keel ruling first; the reviewer compares the cells with INV-NOTE-3.
