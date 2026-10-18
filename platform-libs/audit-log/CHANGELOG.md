# Changelog

All notable changes to `audit-log` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [3.1.4] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [3.1.3] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6291, G-DVS): depends on `outbox-and-queue` 6.1.0; no code change.

## [3.1.2] - 2026-10-18
### Changed
- Metadata-only release (INV-T-4202, G-DVS): depends on `outbox-and-queue` 6.0.1; no code change.

## [3.1.1] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5306, G-DVS): depends on `service-runtime` 8.1.4 and `outbox-and-queue` 6.0.0; no code change.

## [3.1.0] - 2026-10-18
### Added
- `OverrideListing.list(OverrideListQuery): OverrideListingResult`, implemented by `JdbcOverrideService`: the override register of an entity over a transaction-date range (grants with no date are returned in `undated`), the read audited in the same transaction as `listOverrides` (INV-T-6135, INV-REQ-1376, INV-AC-1329, INV-Q-6129 A).
### Changed
- `AuditSchema.ddl`: `override_grant` gains `entity_ref text not null`, `transaction_date date` and the index `override_grant_tenant_entity_date`; services embed `ddl` in a migration they have not yet written (INV-T-6135). `app_role` still has `select, insert` only.

## [3.0.5] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [3.0.4] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5305, G-DVS): depends on `service-runtime` 8.1.1 and `outbox-and-queue` 5.0.0; no code change.

## [3.0.3] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5821, ruling INV-Q-5678 A): depends on `service-runtime` 8.1.0 and `outbox-and-queue` 4.0.2; no code change.

## [3.0.2] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5678 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.


## [3.0.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5304, owner ruling INV-Q-5836 A, version-file scope of the downstream closure of outbox-and-queue 4.0.0): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs name outbox-and-queue at the stepped version.

## [3.0.0] - 2026-10-16
### Changed
- BREAKING (INV-T-5301, slice 1 of 3 of INV-T-4516; owner rulings INV-Q-4196 (A), INV-Q-4508 (A)): `LocalAuditWriter` and `JdbcOverrideService` run every statement through jOOQ on a `DSLContext` from tenancy-jooq's factory (`TenancyJooq.dslContext`), with the module's table named `DSL.name(schema, table)` and declared `TENANT`; identifiers are `DSL.name(...)` of `SqlIdentifier`-validated names and every value is a bind value. Their first constructor parameter is that `DSLContext`; the `CurrentConnection` parameter is gone, so they run on the `TransactionRunner` whose data source holds the lane gate (lane behaviour unchanged, INV-Q-5243). Behaviour is unchanged: the same rows, the same refusals, `ON CONFLICT DO NOTHING` on the override grant. `audit-log` depends on tenancy-jooq.
- The module's tests reach the database only through `TestPoolFixture`, a `TransactionRunner`, a tenancy-jooq `DSLContext` and `TenancyProbe`; the fixtures' DDL is init SQL.

## [2.3.6] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [2.3.5] - 2026-10-16
### Changed
- Metadata-only release (INV-T-4515, version-file scope of the downstream closure): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name the sibling modules at their current versions (tenancy-kernel 3.0.0, outbox-and-queue 3.1.0 and, where depended on, tenancy-jooq 1.1.0 and service-runtime 8.0.0).

## [2.3.4] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5384, coordinator ruling INV-Q-5382, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 2.3.3.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: tenancy-kernel 2.0.0 (was 1.5.0) and outbox-and-queue 3.0.1 (was 2.2.2).

## [2.3.3] - 2026-10-14
### Fixed
- Every blank check uses tenancy-kernel's `BlankText` instead of `btrim` (spaces only) (coordinator rulings INV-Q-4614 (A) and INV-Q-4622 (A), INV-T-4616):
  `purge_audit_entries` (`app.actor`, `p_reason`, `p_subject` and the three ids) and the CHECKs on the audit entry
  actor, action, scope and reason and the override grant reason. Actor values (`app.actor`, the audit entry actor and
  a new CHECK on the override grant actor) also refuse any ISO control character, as do `AuditEntry` and
  `OverrideCommand` (IllegalArgumentException). Refusals keep their error codes and messages; the actor is no longer
  trimmed before it is stored.

## [2.3.2] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [2.3.1] - 2026-10-13
### Fixed
- The published impl POM (`audit-log`) declared no dependency (INV-T-4356; defect in INV-T-2860's and INV-T-3937's
  per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with `artifact(implJar)`
  and no software component, so a consumer declaring platform-libs-bom and this impl artefact had to list
  `audit-log-api` and the other modules itself. The impl POM now declares `audit-log-api` at the same version and the
  first-level dependencies of the impl runtime classpath, generated and checked by the root build.gradle.kts
  (`verifyImplPoms`): audit-log-api:2.3.1 (compile), outbox-and-queue:2.1.1 (runtime), outbox-and-queue-api:2.1.1
  (compile), tenancy-kernel:1.3.1 (compile), tenancy-kernel-api:1.3.1 (compile),
  org.jetbrains.kotlin:kotlin-stdlib:2.4.20 (compile), tools.jackson.core:jackson-databind:3.2.3 (compile). The
  classes are unchanged.

## [2.3.0] - 2026-10-12
### Added (INV-T-3983)
- `OutboxAuditWriter` implements `AuditStager` (coordinator rulings INV-Q-4254 A and INV-Q-4277): `stage(entry,
  context)` returns the `AuditEntryRecorded` outbox row `record` would write, built over outbox-and-queue 2.1.0's
  `OutboxWriter.stage` (envelope under EmissionGuard, no `docsequence`), and inserts nothing; a staged row equals the
  row `record` writes (test). Only an unsequenced entry is staged (no Doc UUID, no aggregate version). It serves
  config-resolver's `write_installation_setting` in a service with an outbox (INV-ADR-3968 item 6, owner ruling
  INV-Q-3931), as `LocalAuditWriter` does in a service without one.

## [2.2.0] - 2026-10-12
### Added
- `AuditStager.stage(entry, context)` and `StagedAuditEntry` (api): the build-only half of an audit writer; it
  returns the row `record` would write (UUIDv7 id, recorded time, source, subject, instance, correlation,
  causation, payload) and inserts nothing. `LocalAuditWriter` implements it; `record` is `stage` plus the insert
  (coordinator rulings INV-Q-4277 B and INV-Q-4254 A; `OutboxAuditWriter` gains it with INV-T-3983).
- `AuditSchema.purgeDdl(schema, runtimeRole, sysRole, service)`: the audited purge of `audit_entry`,
  `purge_audit_entries(cut_off, reason, entry_id, source, subject, instance_id, correlation_id, causation_id)`,
  a SECURITY DEFINER function owned by the NOLOGIN `<svc>_sys`, static SQL,
  `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE only for the runtime login (owner ruling INV-Q-3953,
  INV-Q-3097 as revised, coordinator ruling INV-Q-4038). It refuses an unset `app.actor` (INV-Q-3997), a tenant
  other than the platform tenant, a cut-off younger than the 5-year legal floor (INV-REQ-2588, INV-REQ-1378), and
  staged fields that are blank, a source other than `/gst/<service>`, or an entry id that is not a new UUIDv7;
  then writes its own entry (recorded at the database's `clock_timestamp()`, never a caller's time, coordinator
  ruling INV-Q-4316; actor from `app.actor`; platform tenant and entity; action `purgeAuditEntries`,
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
