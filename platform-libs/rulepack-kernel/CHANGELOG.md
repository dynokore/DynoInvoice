# Changelog

All notable changes to `rulepack-kernel` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

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
    (refused when blank) and validates every argument.
  - `referenceTable(schema, table)`: the name a holder declares as a tenancy-jooq `REFERENCE` table.
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
