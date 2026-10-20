# Changelog

All notable changes to `config-resolver` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [5.0.5] - 2026-10-20
### Changed
- Metadata-only release (INV-T-7520, G-DVS): depends on `tenancy-kernel 4.1.0`, whose published module metadata changed; no code change.

## [5.0.4] - 2026-10-19
### Changed
- detekt type-resolution findings fixed (platform-build 0.21.0, INV-Q-4629); no behaviour or API change

## [5.0.3] - 2026-10-19
### Changed
- Metadata-only release (INV-T-7341, G-DVS): depends on `outbox-and-queue` 6.2.0, whose published module metadata changed; no code change.

## [5.0.2] - 2026-10-19
### Changed
- Metadata-only release (INV-Q-5337, G-DVS): depends on `tenancy-kernel` 4.0.0, whose published module metadata changed; no code change.

## [5.0.1] - 2026-10-19
### Changed
- Metadata-only release (INV-T-7098, INV-Q-5337, G-DVS): depends on `outbox-and-queue` 6.1.5, whose published module metadata changed; no code change.

## [5.0.0] - 2026-10-19
### Changed
- BREAKING (INV-T-5362, slice 5 of INV-T-4156; INV-Q-5352, INV-Q-6663): `write_installation_setting`, `config_purge_subject` and `config_purge_tenant` read the tenant and actor from `<schema>.bound_tenant()` and `<schema>.bound_actor()` (the signed binding of tenancy-kernel), no longer from `app.tenant_id` and `app.actor`. `ConfigSchema.ddl` neither runs `BindingKeyMigration.ddl` nor switches role: the schema must already hold `<schema>.bound_tenant()` and `<schema>.bound_actor()`, created once per schema as `<svc>_sys`, before this module's DDL runs (who runs it in an installation is INV-Q-6580). A refusal that named `app.actor` as unset now reads `the bound actor is not set: ...` (INV-Q-6588); the SQLSTATE is unchanged. Comments name the tenant binding of tenancy-kernel.

## [4.1.3] - 2026-10-19
### Changed
- Metadata-only release (INV-T-5363, G-DVS): depends on `outbox-and-queue` 6.1.4, whose published module metadata changed; no code change.

## [4.1.2] - 2026-10-18
### Changed
- Metadata-only release (INV-T-4224, G-DVS): depends on `tenancy-jooq` 1.3.0, whose published module metadata changed; no code change.

## [4.1.1] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5365, G-DVS): depends on `tenancy-jooq` 1.2.3, whose published module metadata changed; no code change.

## [4.1.0] - 2026-10-18
### Added
- `OptionValueMaximum` port with `JdbcOptionValueMaximum`, and the sys-owned function `config_option_value_maximum` (`ConfigSchema.OPTION_VALUE_MAXIMUM`): the largest numeric member of an option's stored value over every tenant and scope, readable in a platform transaction only (INV-T-6538; INV-Q-6529 A, INV-Q-6527 A). `ConfigSchema.ddl` gives the sys role `select` on nine columns of `config_setting` and a select-only policy. A service embeds the function by a Flyway migration of its own.

## [4.0.10] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5361, G-DVS): depends on `audit-log` 4.0.0, whose published module metadata changed; no code change.

## [4.0.9] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [4.0.8] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6291, G-DVS): depends on `outbox-and-queue` 6.1.0; no code change.

## [4.0.7] - 2026-10-18
### Changed
- Metadata-only release (INV-T-4202, G-DVS): depends on `outbox-and-queue` 6.0.1; no code change.

## [4.0.6] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5306, G-DVS): depends on `service-runtime` 8.1.4 and `outbox-and-queue` 6.0.0; no code change.

## [4.0.5] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6135, G-DVS): depends on `audit-log` 3.1.0, whose published module metadata changed; no code change.

## [4.0.4] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [4.0.3] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5305, G-DVS): depends on `service-runtime` 8.1.1 and `outbox-and-queue` 5.0.0; no code change.

## [4.0.2] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5821, ruling INV-Q-5678 A): depends on `service-runtime` 8.1.0 and `outbox-and-queue` 4.0.2; no code change.

## [4.0.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5678 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.

## [4.0.0] - 2026-10-17
### Changed (breaking)
- `JdbcSettingStore`, `JdbcSettingWrites`, `JdbcPolicyReader`, `JdbcPolicyHistory` and `JdbcPolicyWriter` take a
  `DSLContext` (tenancy-jooq's `TenancyJooq.dslContext`, built over the service's `TransactionRunner`) instead of a
  `CurrentConnection`, and run every statement as jOOQ DSL (INV-T-4517, slice 2 of 2; INV-ADR-3968 item 4c, INV-REQ-854 b8;
  owner ruling INV-Q-4196). Identifiers are `DSL.name` of the `SqlIdentifier`-validated schema, every value is a bind value,
  the definer function `write_installation_setting` is a jOOQ function call. Behaviour, lane (the DSLContext runs on the
  `TransactionRunner` whose `TenantAwareDataSource` holds the lane gate, INV-Q-4502 (A)) and tenant predicates are unchanged.
- `PolicyReader.resolve(key, scope, at, options: KClass<E>)` becomes `resolve(key, scope, at, options, entries: List<E>)`: the
  enum constants come from `enumEntries<E>()` of the reified extension, no longer from `Class.getEnumConstants`.
### Added
- `JdbcPolicyWriter.audit` and `JdbcPolicyWriter.delegation`: public read-only properties returning the constructor
  arguments (coordinator ruling INV-Q-5117).
### Removed
- `SelectedOptionsColumns` (JDBC); `SelectedOptionsJooq` is the one codec of the two value columns.

## [3.0.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5304, owner ruling INV-Q-5836 A, version-file scope of the downstream closure of outbox-and-queue 4.0.0): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs name outbox-and-queue at the stepped version.

## [3.0.0] - 2026-10-16
### Changed (breaking)
- `JdbcConfigPurge` and `JdbcResolutionRecorder` take a `DSLContext` (tenancy-jooq's `TenancyJooq.dslContext`, built over the
  service's `TransactionRunner`) instead of a `CurrentConnection`, and run every statement as jOOQ DSL (INV-T-5303, slice 1
  of 2 of INV-T-4517; INV-ADR-3968 item 4c, INV-REQ-854 b8; owner ruling INV-Q-4196). Identifiers are `DSL.name` of the
  `SqlIdentifier`-validated schema, every value is a bind value, the definer functions `config_purge_subject` and
  `config_purge_tenant` are jOOQ function calls. Behaviour is unchanged, and so are the lane (the DSLContext runs on the
  `TransactionRunner` whose `TenantAwareDataSource` holds the lane gate, owner ruling INV-Q-4502 (A)) and the tenant
  predicate (tenancy-jooq adds it and checks it again).
### Added
- `ConfigTables.declared(schema)`: the module's tables for `TenancyJooq.dslContext`, named with `DSL.name(schema, table)` and
  declared with their class (`config_setting`, `config_resolved_value`, `config_relaxation_acknowledgement` TENANT;
  `config_installation_setting` PLATFORM_SCOPED, by its table comment). The JDBC `SelectedOptionsColumns` stays for
  `JdbcSettingStore` and `JdbcSettingWrites` until the last slice (INV-T-4517) deletes it.

## [2.1.5] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5301, coordinator ruling INV-Q-5688, version-file scope of the downstream closure of audit-log 3.0.0): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name audit-log, which now carries tenancy-jooq and jOOQ, so a published version is not replaced.

## [2.1.4] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [2.1.3] - 2026-10-16
### Changed
- Metadata-only release (INV-T-4515, version-file scope of the downstream closure): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name the sibling modules at their current versions (tenancy-kernel 3.0.0, outbox-and-queue 3.1.0 and, where depended on, tenancy-jooq 1.1.0 and service-runtime 8.0.0).

## [2.1.2] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5384, coordinator ruling INV-Q-5382, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 2.1.1.
- The POMs and module metadata of both publications (api and impl) now name audit-log 2.3.4 (was 2.3.3).

## [2.1.1] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5339, coordinator ruling INV-Q-5337, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 2.1.0.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: tenancy-kernel 2.0.0 (was 1.5.0), outbox-and-queue 3.0.1 (was 3.0.0), audit-log 2.3.3.

## [2.1.0] - 2026-10-15
### Changed
- `ConfigSchema.ddl` gives `config_installation_setting` the table comment `platform-scoped: no tenant data`, and
  `ConfigSchema.platformScopedTables(schema)` lists it for the service's tenant-table declaration (INV-T-4134;
  INV-ADR-3968 item 4a, INV-REQ-854 b6; owner ruling INV-Q-4113). Clears the module's platform-build 0.10.0
  `tenant-column` finding.

## [2.0.4] - 2026-10-14
### Fixed
- `Actor.principal` uses tenancy-kernel's `BlankText.isActor` instead of `isNotBlank()` (coordinator ruling
  INV-Q-4684 (A), INV-T-4686; INV-Q-4534, INV-Q-4614, INV-Q-4622): a principal that is blank by the shared set or
  holds an ISO control character is refused with an `IllegalArgumentException` that never echoes the value.

## [2.0.3] - 2026-10-14
### Fixed
- Every blank check uses tenancy-kernel's `BlankText` instead of `btrim` (spaces only) (coordinator rulings INV-Q-4614 (A) and INV-Q-4622 (A), INV-T-4616):
  `write_installation_setting` (`app.actor`, `p_reason`, the staged instance, correlation and causation ids),
  `config_purge_subject` and `config_purge_tenant` (`app.actor`), and the CHECKs on `scope_id`, `set_by`, `reason`,
  `subject_ref`, `role` and `principal`. Actor values (`app.actor`, `set_by`, `principal`) also refuse any ISO control
  character. Refusals keep their error codes and messages; the actor is no longer trimmed before it is stored.

## [2.0.2] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [2.0.1] - 2026-10-13
### Fixed
- The published impl POM (`config-resolver`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `config-resolver-api` and the other modules itself. The impl POM now declares `config-resolver-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): audit-log-api:2.3.1 (compile), config-resolver-api:2.0.1 (compile),
  outbox-and-queue-api:2.1.1 (compile), tenancy-kernel:1.3.1 (compile), tenancy-kernel-api:1.3.1 (runtime),
  org.jetbrains.kotlin:kotlin-stdlib:2.4.20 (compile), tools.jackson.core:jackson-databind:3.2.3 (compile). The
  classes are unchanged.

## [2.0.0] - 2026-10-12
### Changed (breaking)
- One runtime login per service (INV-REQ-1189 b2, INV-ADR-3968; owner rulings INV-Q-3931, INV-Q-3953, INV-T-3983):
  `ConfigSchema.ddl(schema, runtimeRole, sysRole, mode)` replaces `ddl(schema, appRole, platformRole, purgeRole)`.
  The runtime login gets SELECT and INSERT on the tenant tables, SELECT only on `config_installation_setting`, no
  UPDATE or DELETE anywhere, and EXECUTE on the functions below; the NOLOGIN `<svc>_sys` owns them. `mode`
  (`EventMode.OUTBOX`, the default, or `NO_OUTBOX`) fixes in the schema where an installation value's events go,
  never an argument (INV-Q-3142, coordinator ruling INV-Q-4254 A).
- `write_installation_setting(...)`, SECURITY DEFINER: the only way to store an installation value. It takes the
  value and the audit entry and `PolicyChanged` rows staged in Kotlin by their owning modules (audit-log's
  `AuditStager`, coordinator ruling INV-Q-4277; outbox-and-queue's `OutboxWriter.stage`), checks before any insert
  that the actor is `app.actor` (refused unset) and the audit entry's actor, the bound tenant and every row are the
  platform's, types and topics are fixed (`AuditEntryRecorded` on gst.platform.audit-entry-recorded.v1,
  `PolicyChanged` on gst.platform.policy-changed.v1), and the audit action and scope and the `PolicyChanged`
  scope, vpId, value and effectiveDate equal the value, and the audit entry carries its prior state as an object
  (INV-REQ-2588 b2, INV-AC-2229); a mismatch refuses with nothing written (INV-Q-4254 A).
  It then inserts the value, the audit entry (outbox row, or local `audit_entry` row) and `PolicyChanged` (outbox
  mode) together, every row at one `clock_timestamp()`; a staged time is never taken (coordinator ruling
  INV-Q-4316). `JdbcPolicyWriter` writes an installation value only through it and needs its `AuditWriter` to be
  an `AuditStager` (fail closed); the version it returns carries the stored id and time.
- `config_purge_subject` and `config_purge_tenant`, SECURITY DEFINER: `JdbcConfigPurge` calls them on the runtime
  login in the tenant's transaction; they refuse an unset `app.actor`, an unbound or platform tenant and a purge of
  another tenant than the bound one, and delete under the tenant policy (INV-Q-2938, INV-Q-3997). Purging another
  tenant from a tenant's transaction is now refused rather than a no-op.
- Every function has static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE revoked from PUBLIC and
  granted to the runtime login only (coordinator ruling INV-Q-4038).
- Main code binds no tenant (coordinator ruling INV-Q-4256): the caller binds the tenant and the actor it holds
  (`TenantContext.bind(tenant, actor)`, `bindPlatform(actor)`; INV-Q-3997, coordinator ruling INV-Q-4268).

### Removed
- `ConfigSchema.upgradeTo110` and `upgradeTo120`, which took the former roles: there is no upgrade path from 1.x;
  a service creates the 2.0.0 schema afresh (coordinator ruling INV-Q-4068).
- The internal `SqlIdentifier` copy: identifiers enter SQL text only through tenancy-kernel's
  `SqlIdentifier.require`, generated statement groups are joined outside SQL text, and `JdbcResolutionRecorder`
  uses one static statement (INV-REQ-854 b5; coordinator rulings INV-Q-4036, INV-Q-4040). Zero findings from
  platform-build 0.9.0's SqlGuards and MigrationLint.

### Tests
- On PostgreSQL 16, 17 and 18 (owner ruling INV-Q-3965), in config-resolver's `test` source set (INV-Q-4256), in
  outbox and no-outbox mode: the write is all or none, the runtime login's direct INSERT is refused (and the
  catalogue shows no such privilege), one planted fault per check of the function, a backdated staged time is
  stored as database time, and a purge removes only the subject's or the tenant's rows. Jdbc statements stay
  static and parameterised; the jOOQ conversion is INV-T-4202 / INV-T-4203 (owner ruling INV-Q-4196).

## [1.4.0] - 2026-10-12
### Changed
- The `PolicyChanged` payload is exactly the INV-API-3306 data model (scope, vpId, value, effectiveDate, source
  and, when present, relaxationAcknowledgementReference): it no longer carries `settingId`, `profileId` or
  `profileVersion`, which stay on the setting record where INV-AC-2092 reads them (owner ruling INV-Q-2979,
  coordinator ruling INV-Q-3714, INV-T-3722). The audit entry's prior state and the setting record are unchanged.
  The three fields were never in the contract, so no contract version changes.

## [1.3.0] - 2026-10-11
### Added
- `TypedValueSpec.default` (gst-catalogue 1.6.0 `TypedValue.default`) and `TypedValueSpec.absentAs`: an option
  without a value counts by its catalogue default, else its fixed number (INV-T-3381).

### Changed
- VP-08's DEFAULT_PAIR_4H window is no longer treated as fixed at 4 h (INV-Q-3204, superseded by owner ruling
  INV-Q-3320 A′): it is checked against the catalogue's typed bounds (1 to 8 hours, default 4, DOWN_STRICTER),
  where nothing is set at a scope the catalogue default resolves with its default window (`{"window":4}`) and
  counts as 4 h in the cross-scope check and the cap; a write without a value or without the window is still
  refused (INV-Q-3167, INV-Q-3548); tighten-only across scopes is unchanged (INV-REQ-1269, INV-REQ-896 b2,
  INV-Q-3206).
- A typed catalogue-default option resolves with the value built from its typed defaults (a number with a
  numeric `default`, or a record whose every field has one); otherwise without a value, as before.

## [1.2.0] - 2026-10-11
### Added
- Cross-scope tighten-only (VP-08): a narrower value's directed fields may not be looser than the broader
  scope's value, compared by field name; undirected fields are never compared (INV-REQ-780 b1, INV-Q-2936,
  INV-Q-3204). Refusal `LOOSER_THAN_BROADER`.
- Installation bound (VP-57, VP-67): `Bound.row`, `VariationPointDefinition.installationParameter`; the
  bounding INSTALLATION row is resolved in the same service (INV-REQ-1248, INV-Q-3202). Refusal
  `BEYOND_INSTALLATION_BOUND`; an unevaluable bound refuses `BOUND_UNAVAILABLE` (fail closed).
- Resolution caps a stored value's limited fields at the broader value or bound in force on the date, the
  stored value unchanged: `ResolvedSetting.caps`, `PolicyCap`, `PolicyReader.capToBounds` for per-object
  values, `BoundUnavailable` (INV-REQ-1242 b5, INV-Q-3206).
- `ConfigSchema.upgradeTo120`: `config_resolved_value.capped_by`; `RecordedResolution.caps`.
- `TypedValueSpec.boundingRows`, `TypedValueSpec.fixed`.

### Changed
- The 1.1.0 `NOT_SUPPORTED` refusal now applies only to what cannot be evaluated: an installation bound with no
  installation parameter row, or a cross-scope row whose options do not all carry the same directed fields
  (INV-Q-3173).

## [1.1.0] - 2026-10-11
### Added
- `PolicyWriter`: `setPolicy` at every allowed scope including installation, with role, reason, bounds,
  effective-date and back-dating checks, an audit entry holding the prior value and `PolicyChanged` through
  the outbox where present; `acknowledgeControlRelaxation`; `validate` (INV-T-2870, INV-AC-2227,
  INV-AC-2224, INV-AC-2229, INV-AC-205, INV-AC-2230, INV-AC-2234).
- `PolicyHistory`: versions, as-of view and compare (INV-AC-1146).
- Ports `DelegationProbe`, `BackdatingFloor`, `ValueConstraints`; `VariationPointCatalogue.keys()`;
  `OptionSpec.typedValue` and `OptionSpec.acknowledgers` (INV-Q-3134..3142, INV-Q-3165..3173).
- `ConfigSchema.upgradeTo110`: `config_relaxation_acknowledgement` (RLS forced, insert-only for the app role)
  and `config_setting.relaxation_ref`; `ConfigPurge.purgeTenant` also removes acknowledgements.

## [1.0.0] - 2026-10-11
### Added
- `PolicyReader` over seven scope levels, then the operating profile at its version, then the catalogue
  default (by form where the row says so), at the transaction date (INV-REQ-1068, INV-REQ-1242,
  INV-REQ-1351, INV-REQ-1251; INV-T-2869).
- `VariationPointCatalogue` port shaped on gst-catalogue 1.2.0: scope levels, option keys, per-option
  strictness, multi-select, by-form default (INV-Q-2934, INV-Q-2936).
- `ResolutionRecorder` (insert-only, first write wins) and `ConfigPurge` (`purgeSubject`, `purgeTenant`)
  (INV-Q-2938).
- `BusinessDate.today(clock)`: the Asia/Singapore business date for as-of views (INV-Q-2940).
- `ConfigSchema`: `config_setting` (source `OVERRIDE`/`PROFILE`, profile id and version, INV-Q-2979),
  `config_installation_setting`, `config_resolved_value`; RLS enabled and forced on the tenant tables.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
