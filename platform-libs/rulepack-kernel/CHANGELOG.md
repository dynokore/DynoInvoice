# Changelog

All notable changes to `rulepack-kernel` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [4.1.2] - 2026-10-26
### Changed
- Metadata-only release (INV-T-11066, INV-Q-11056, G-DVS): depends on `tenancy-kernel 4.2.0`, whose published module metadata changed; no code change.

## [4.1.1] - 2026-10-26
### Changed
- Generated SQL changes in text only (the blank set does not change; INV-Q-10832, INV-Q-10848, INV-T-10836): the `BlankText` constants of tenancy-kernel 4.1.1 are written with ASCII escapes and Kotlin inlines them at this module's compile time.

## [4.1.0] - 2026-10-22
### Added
- `VerifiedPack` (private constructor, factory `VerifiedPack.of(Verified, PackArtefact, TenantId?)`) and `PackRegistry.register(VerifiedPack)`, whose default calls `register(PackVersion)`; `FenceParticipant.onArmed` now registers through it, so a registry that persists receives the signed artefact and the trust anchor label (INV-Q-8206, INV-T-8211). Additive.

## [4.0.0] - 2026-10-20
### Changed
- BREAKING: the pack registry table created by `PackRegistryMigration.ddl` renames its column `version` to `pack_version` (INV-Q-7438), so `gst.jooq-codegen` accepts the table; the lineage key becomes `UNIQUE (pack_type, tenant_id, pack_version)`; the function `register_platform_pack` keeps its signature and the parameter name `p_version` (INV-T-7524).

## [3.0.3] - 2026-10-20
### Changed
- Metadata-only release (INV-T-7520, G-DVS): depends on `tenancy-kernel 4.1.0`, whose published module metadata changed; no code change.

## [3.0.2] - 2026-10-19
### Changed
- detekt type-resolution findings fixed (platform-build 0.21.0, INV-Q-4629); no behaviour or API change

## [3.0.1] - 2026-10-19
### Changed
- Metadata-only release (INV-Q-5337, G-DVS): depends on `tenancy-kernel` 4.0.0, whose published module metadata changed; no code change.

## [3.0.0] - 2026-10-18
### Changed
- BREAKING: `PackRegistryMigration.ddl` reads the tenant and the actor only through the signed binding of tenancy-kernel: its policies and the `register_platform_pack` and `registered_by` trigger functions use `(select <schema>.bound_tenant())` and `(select <schema>.bound_actor())`, and the DDL needs those two functions in the schema before it runs. It does not create them and issues no role switch: run tenancy-kernel's `BindingKeyMigration.ddl` once per schema as the `<svc>_sys` login first (who runs it in an installation is INV-Q-6580, unanswered). On a schema without them the DDL raises SQLSTATE 42883 at the first policy that names `bound_tenant()` and creates nothing. A holder declares `<schema>.binding_key` as platform-scoped in its `TableClasses`, and a session with no valid `app.binding_sig` sees and writes nothing. The refusal message that named `app.actor` as unset now reads `the bound actor is not set: ...`, same SQLSTATE (INV-Q-5352, INV-Q-6663, INV-Q-6588, slice 7 of 9 of INV-T-4156). Services stay off this version until INV-T-4156 (INV-Q-5356 A).

## [2.0.9] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [2.0.8] - 2026-10-18
### Changed
- The forbidden pack field names `exec` and its long spelling are held as one anchored case-insensitive pattern, so no string literal reads as a bare SQL keyword to the migration lint (INV-T-6394); the validator refuses and accepts exactly the same field names.

## [2.0.7] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [2.0.6] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5678 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.

## [2.0.5] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [2.0.4] - 2026-10-16
### Changed
- Metadata-only for the published artefacts (INV-T-4515, version-file scope of the downstream closure): no API or behaviour change; the POMs and module metadata name tenancy-kernel 3.0.0 and tenancy-jooq 1.1.0.
- Test classpath only: org.reactivestreams:reactive-streams (from jOOQ through tenancy-jooq) pinned to 1.0.4 (MIT-0) in
  place of 1.0.3 (CC0-1.0) (INV-T-4645; coordinator ruling INV-Q-4643 (A), delegated, owner 2026-10-14). Nothing
  published changes.

## [2.0.3] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5340, coordinator ruling INV-Q-5337, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 2.0.2.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: tenancy-kernel 2.0.0 (was 1.5.0), crypto 1.1.0 (was 1.0.2).

## [2.0.2] - 2026-10-14
### Fixed
- `PackRegistryMigration` uses tenancy-kernel's shared `BlankText` instead of its own code-point literal (coordinator rulings INV-Q-4614 (A) and INV-Q-4622 (A), INV-T-4616):
  the `app.actor` reads of `register_platform_pack` and the insert trigger and the `registered_by` CHECK also refuse an
  actor holding any ISO control character, and the pack fields use the shared blank rule instead of `btrim`.

## [2.0.1] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [2.0.0] - 2026-10-13
### Changed (breaking)
- `PackRegistryMigration` stores platform-wide pack rows under the reserved platform tenant and writes them only
  through a sys-owned definer function (coordinator ruling INV-Q-4117, owner ruling INV-Q-2927 D, INV-ADR-3968 items
  4a and 6, INV-REQ-854 b2; INV-T-4137):
  - `tenant_id` is `uuid NOT NULL`; a platform-wide row carries `TenantId.PLATFORM`. The lineage key is a plain
    `UNIQUE (pack_type, tenant_id, version)`.
  - `ddl(schema, table, runtimeRole, sysRole)` replaces `ddl(schema, table)`. Row-level security (enabled and
    forced) is keyed on `app.tenant_id` only: SELECT shows the bound tenant's rows plus the platform rows, and nothing
    with no tenant bound; INSERT, UPDATE and DELETE only the bound tenant's own rows, never a platform row.
  - Platform rows are inserted only by `<schema>.register_platform_pack(...)`
    (`REGISTER_PLATFORM_PACK`, `REGISTER_PLATFORM_PACK_ARGUMENTS`): SECURITY DEFINER, owned by the NOLOGIN sys
    role, static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to
    the runtime login only. It runs under the platform-tenant binding only, takes the actor from `app.actor`
    (refused when unset, NULL or blank, nothing written), stores it as the row's `registered_by` and validates every
    argument. The migrating role (`<svc>_migrate`, NOSUPERUSER) must be a
    member of the sys role: it needs SET ROLE on it for `ALTER FUNCTION ... OWNER TO`.
  - Every row carries `registered_by text NOT NULL`, the registering principal (owner ruling INV-Q-4580 (B)): a
    BEFORE INSERT trigger refuses a row whose `registered_by` is blank or not the transaction's `app.actor`, and a
    BEFORE UPDATE trigger refuses any change to it, so it is fixed at insert. Blank means empty or only ISO control
    characters and the caseId whitespace set of coordinator ruling INV-Q-4534 (tab, newline, CR, NBSP, U+2000-U+200A,
    U+3000, U+FEFF and the rest of that list), not `btrim`'s spaces only (review of !66). A holder's own tenant insert sets
    `registered_by` to its `app.actor`.
  - `referenceTable(schema, table)`: the name a holder **must** declare as a tenancy-jooq `REFERENCE` table. A test
    shows tenancy-jooq's `TableClasses.verify` classifies the migrated table `REFERENCE` with it declared, fails on a
    wrongly declared name (declared but missing), and classifies it `TENANT` when undeclared, under which tenant reads
    silently drop the platform rows; the holder's runtime test proves that loss (INV-Q-4582, INV-Q-4596, INV-MOD-53).
### Removed
- The `app.platform_operator` session setting and its policy branch, every `tenant_id IS NULL` path, and
  `lineageKeyUpgradeDdl` (the 1.x lineage-key upgrade). There is no upgrade path from 1.x: no service consumes the
  registry table yet.
- rulepack-kernel has zero platform-build 0.10.0 `tenant-column` findings and leaves SqlGuardsTest's 0.10.0 pending
  list (coordinator ruling INV-Q-4185).

## [1.3.3] - 2026-10-13
### Fixed
- The published impl POM (`rulepack-kernel`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `rulepack-kernel-api` and the other modules itself. The impl POM now declares `rulepack-kernel-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): crypto:1.0.1 (runtime), crypto-api:1.0.1 (compile), rulepack-kernel-api:1.3.3
  (compile), tenancy-kernel-api:1.3.1 (runtime), org.bouncycastle:bcpkix-jdk18on:1.86 (runtime),
  org.bouncycastle:bcprov-jdk18on:1.86 (runtime), org.jetbrains.kotlin:kotlin-stdlib:2.4.20 (runtime),
  tools.jackson.core:jackson-databind:3.2.3 (compile). The classes are unchanged.

## [1.3.2] - 2026-10-12
### Changed
- `PackRegistryMigration.ddl` and `lineageKeyUpgradeDdl` interpolate identifiers only through tenancy-kernel's
  `SqlIdentifier.require` (tenancy-kernel 1.3.0), including the names derived from the table
  (`<table>_resolution_idx`, `<table>_lineage_key`, `<table>_tenant_isolation`); the module's own regex
  validator is removed. A name with a leading underscore, or a table whose derived names exceed 63 characters,
  is now refused instead of being silently truncated by PostgreSQL. The DDL for a valid name is unchanged. The
  module has zero platform-build 0.9.0 SQL guard findings (INV-REQ-854 b5, INV-ADR-3968 item 11; coordinator
  rulings INV-Q-4036, INV-Q-4040; INV-T-4043).

## [1.3.1] - 2026-10-12
### Fixed
- The published `rulepack-kernel-api` jars 1.2.0 and 1.3.0 held no api classes (INV-T-3937; coordinator ruling
  INV-Q-3929, defect in INV-T-2860's per-module publishing, STK-60, FR-L.6): `implJar` wrote the same build/libs file
  as the api `jar`, so each `-api` jar is byte-identical to `rulepack-kernel` and holds only the impl classes. Fixed on
  main by df85a19 (`implJar` writes to build/libs-impl) and released here. No source or API change.

## [1.3.0] - 2026-10-11
### Changed
- `BouncyCastlePackVerifier`'s detached CMS check and SHA-256 checksums now run on the platform
  `crypto` module (CS-53; INV-Q-3356 M-A; INV-T-3358 / FU-E2), which the module now depends on.
  The constructor gains two parameters: `signatureVerifier: DetachedSignatureVerifier`, defaulting
  to crypto's `BouncyCastleCms`, and `signatureAlgorithm: SignatureAlgorithm`, defaulting to
  `ECDSA_P256_SHA256` (STK-13: the algorithm is a configuration of rulepack-kernel).
  `BouncyCastlePackVerifier()` and `BouncyCastlePackVerifier(objectMapper)` are unchanged. Step order, rejection reasons and details are unchanged.
- The signature is now checked under the configured algorithm, ECDSA P-256 with SHA-256 by default (STK-13): a pack
  whose signer uses another signature algorithm, digest or key curve is `SIGNATURE_INVALID`
  (previously any algorithm Bouncy Castle could verify was accepted, then pinned to a trust anchor).

## [1.2.0] - 2026-10-11
### Fixed
- The registry lineage key is now `UNIQUE NULLS NOT DISTINCT (pack_type, tenant_id, version)`, so a platform
  pack version (`tenant_id IS NULL`) cannot be stored twice (INV-Q-2964; INV-T-2996 / FU-A7). Tenant rows are
  unchanged.
### Added
- `PackRegistryMigration.lineageKeyUpgradeDdl`: the forward-only migration for a holder whose table already
  exists with the old constraint (PostgreSQL 15 or later).

## [1.1.0] - 2026-10-11
### Added
- The fence participant (INV-REQ-1166, INV-REQ-1009; INV-T-2867 / T23): `FenceParticipant` verifies a
  staged pack again, refuses executable content, compiles it through the holder's `PackCompiler`, holds it
  unused and acknowledges (`PackReadinessAcknowledgement`, handed to a `ReadinessAcknowledger` port that
  service-runtime binds, never to outbox-and-queue). `onArmed` makes it usable; `onAborted` discards it.
- `ActivationFence`, the coordinator side: arms (one `PackArming` write) only when every expected
  instance acknowledged `Loaded`; any failure, a missing acknowledgement after the window or an
  interrupted arming aborts with nothing armed and `RUL-005`.
- `DryRun`: the dry-run delta over a document sample (changed documents with old and new code, taxable
  and GST value; by period; straddling credit notes apart; evaluation failures listed, never unchanged).
- `DecisionTrace`, `TraceReference` and `BusinessTermRenderer`: the trace of rule, pack version, inputs
  and verdict, rendered in business terms from a pack's catalogue (INV-REQ-1182).

## [1.0.0] - 2026-09-27
### Added
- `PackArtefact`: the signed pack artefact's four named byte members —
  manifest, checksums, detached CMS signature, contents (STK-13).
- `PackVerifier`/`BouncyCastlePackVerifier`: verifies a pack artefact's
  checksums and detached CMS (`SignedData`) signature against `TrustAnchors`
  with Bouncy Castle (ECDSA P-256, SHA-256), pinning the signer to an exact
  trust anchor rather than an open PKIX chain — the current key or a
  pre-published successor key alike (STK-13 rotation), never a certificate the
  artefact merely carries about itself.
- `PackContentValidator`/`JacksonPackContentValidator`: refuses a pack whose
  content carries executable material — a forbidden field name (`script`,
  `handlerClass`, `eval`, …), a recognisable script fragment or shebang, or a
  compiled-code magic number (JVM `.class`, ELF, PE) even base64-encoded
  (INV-AC-2394, INV-REQ-2393, INV-BR-214). A fixture carrying a `script` or
  `handlerClass` field is refused; the product's own `code`/`handlers`
  vocabulary (packs.yaml) is not mistaken for it.
- `PackRegistry`/`InMemoryPackRegistry`: resolves the pack version in force
  for a business/transaction date over immutable versions (INV-REQ-770,
  INV-ADR-263, INV-BR-215), tenant pack over platform pack when both apply
  (the one point this module depends on `tenancy-kernel`, INV-ADR-2774); a
  conflicting re-registration of an already-registered version is refused
  (INV-REQ-1089: a pack version never changes once registered). Proved by a
  Kotest property test (STK-38) against an independent reference
  implementation.
- `PackRegistryMigration`: the DDL (`new_table`) a pack holder includes in its
  own migration for its persisted registry (INV-REQ-1195: "in its own schema
  or memory"), the same shape for every holder.
- Consumer-driven contract test of the module API (verify → validate → stage
  → resolve), written the way a `rules-svc` use case is.
- `platform-libs-bom` gains this module's `api`/`impl` constraint pair.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
