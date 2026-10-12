# Changelog

All notable changes to `audit-log` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [2.2.0] - 2026-10-12
### Added
- `AuditStager.stage(entry, context)` and `StagedAuditEntry` (api): the build-only half of an audit writer; it
  returns the row `record` would write (UUIDv7 id, recorded time, source, subject, instance, correlation,
  causation, payload) and inserts nothing. `LocalAuditWriter` implements it; `record` is `stage` plus the insert
  (coordinator rulings INV-Q-4277 B and INV-Q-4254 A; `OutboxAuditWriter` gains it with INV-T-3983).
- `AuditSchema.purgeDdl(schema, runtimeRole, sysRole, service)`: the audited purge of `audit_entry`,
  `purge_audit_entries(cut_off, reason, entry_id, recorded_at, source, subject, instance_id, correlation_id,
  causation_id)`, a SECURITY DEFINER function owned by the NOLOGIN `<svc>_sys`, static SQL,
  `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE only for the runtime login (owner ruling INV-Q-3953,
  INV-Q-3097 as revised, coordinator ruling INV-Q-4038). It refuses an unset `app.actor` (INV-Q-3997), a tenant
  other than the platform tenant, a cut-off younger than the 5-year legal floor (INV-REQ-2588, INV-REQ-1378), and
  staged fields that are blank, a source other than `/gst/<service>`, or an entry id that is not a new UUIDv7;
  then writes its own entry (actor from `app.actor`, platform tenant and entity, action `purgeAuditEntries`,
  scope, reason, prior state) before deleting, in the caller's transaction (INV-API-3822). `<svc>_sys` gets
  `SELECT`, `INSERT` and `DELETE` with policies admitting only rows older than the floor; the runtime login keeps
  `SELECT` and `INSERT` only.

### Changed
- Identifiers enter SQL only through tenancy-kernel's `SqlIdentifier.require` (`AuditSchema.identifier` removed;
  `JdbcOverrideService` and `LocalAuditWriter` validate their schema with it), and the RLS trailer is joined as its
  own statement group: zero SqlGuards and MigrationLint findings, audit-log removed from the pending list
  (coordinator rulings INV-Q-4036, INV-Q-4038, INV-Q-4040).

## [2.1.0] - 2026-10-11
### Added
- `LocalAuditWriter`: `AuditWriter.record(entry, ctx)` for a service without an outbox
  (`control-plane-svc`, INV-Q-2958): one row in the local `audit_entry` table of the service's schema, in
  the command's transaction (INV-REQ-1226, -2588); operator actions with no tenant are stored under the
  reserved `TenantId.PLATFORM` (INV-Q-2927 option D). Returns the envelope the outbox writer would stamp.
- `AuditSchema.auditEntryDdl`: the `audit_entry` table, `tenant_id` NOT NULL leading every index, forced
  row-level security, select and insert only; `actor` classed personal, `reason` and `prior_state` content
  (CS-56). Retention per INV-Q-3028: at least the longest tenant VP-43 period, never under 5 years, no
  automatic expiry, deletion only through a future audited operation (its own Keel task; no purge API here).

## [2.0.0] - 2026-10-11
### Changed
- BREAKING: `OverridePolicy` no longer takes `additionalRequiredRoles`; an override needs one
  `tax.approver` per document version (VP-32 default, INV-REQ-1293; INV-Q-2972). The
  "Keel controls are tighten-only" comment is corrected to INV-REQ-1244's rule.

## [1.0.0] - 2026-10-11
### Added
- `AuditWriter`: actor, action, scope, reason and prior state written as `AuditEntryRecorded`
  through the service's outbox in the command's transaction (INV-REQ-861, -1226, -2588).
- `AuditedOperations`: a state change commits with its entry, a read returns only after its
  entry is written; a failed entry commits nothing and returns no result (INV-AC-2593).
- Override path: `OverrideService`, `OverrideRegister`, `ErrorCatalogue`. TERMINAL (and MANUAL
  or unknown) codes are refused before any write; OVERRIDABLE needs a reason and the
  `tax.approver` role (a policy may only add requirements, never widen), is scoped to one document version, and commits with its audit entry
  or leaves nothing (INV-REQ-1179, -1180; INV-AC-2166, -2209).
- `AuditSchema.ddl`: the `override_grant` table with `tenant_id`, forced row-level security,
  select and insert only.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
