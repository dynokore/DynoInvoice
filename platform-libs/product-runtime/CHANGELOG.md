# Changelog

All notable changes to `product-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.14] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [1.0.13] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5305, G-DVS): depends on `service-runtime` 8.1.1 and `outbox-and-queue` 5.0.0; no code change.

## [1.0.12] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5821, ruling INV-Q-5678 A): depends on `service-runtime` 8.1.0 and `outbox-and-queue` 4.0.2; no code change.

## [1.0.11] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5678 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.

## [1.0.10] - 2026-10-17
### Changed
- Metadata-only release (INV-T-4517, version-file scope of the downstream closure of config-resolver 4.0.0 and service-runtime 8.0.6; the publish guard refuses a changed POM at a published version): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name service-runtime 8.0.6 and config-resolver 4.0.0.

## [1.0.9] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5304, owner ruling INV-Q-5836 A, version-file scope of the downstream closure of outbox-and-queue 4.0.0): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs name outbox-and-queue at the stepped version.

## [1.0.8] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5303; the publish guard refuses a changed POM at a published version): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name service-runtime 8.0.4 and config-resolver 3.0.0.

## [1.0.7] - 2026-10-17
### Changed
- Metadata-only release (INV-T-4516, version-file scope of the downstream closure of evidence-store 2.0.0 and service-runtime 8.0.3): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name service-runtime and evidence-store, which now carry tenancy-jooq and jOOQ, so a published version is not replaced.

## [1.0.6] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5301, coordinator ruling INV-Q-5688, version-file scope of the downstream closure of audit-log 3.0.0): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name audit-log, which now carries tenancy-jooq and jOOQ, so a published version is not replaced.

## [1.0.5] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [1.0.4] - 2026-10-16
### Changed
- Metadata-only release (INV-T-4515, version-file scope of the downstream closure; 1.0.3 was released by INV-T-5341 meanwhile): no API or behaviour change; `src/main` and `src/impl` are unchanged. The POMs and module metadata of the publications name the sibling modules at their current versions (tenancy-kernel 3.0.0, outbox-and-queue 3.1.0 and, where depended on, tenancy-jooq 1.1.0 and service-runtime 8.0.0). Its build script drops the shedlock-provider-jdbc-template and spring-jdbc test pins that service-runtime no longer uses (INV-Q-5210).

## [1.0.3] - 2026-10-16
### Changed
- Metadata-only release (INV-T-5341, coordinator ruling INV-Q-5337, delegated, owner 2026-10-14): no API or behaviour
  change; `src/main` and `src/impl` are unchanged since 1.0.2.
- The POMs and module metadata of both publications (api and impl) now name the sibling modules at their current
  versions: service-runtime 7.1.1 (was 5.0.0), config-resolver 2.1.2 (was 2.0.2), outbox-and-queue 3.0.1 (was 2.2.1), tenancy-jooq 1.0.3 (was 1.0.2) and
  tenancy-kernel 2.0.0 (was 1.4.1).

## [1.0.2] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.

## [1.0.1] - 2026-10-13
### Fixed
- The published impl POM (`product-runtime`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `product-runtime-api` and the other modules itself. The impl POM now declares `product-runtime-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): com.dynokore.gst:gst-catalogue:1.14.0 (compile), config-resolver-api:2.0.1
  (compile), outbox-and-queue-api:2.1.1 (compile), product-runtime-api:1.0.1 (runtime), service-runtime:4.0.1
  (compile), service-runtime-api:4.0.1 (compile), tenancy-kernel-api:1.3.1 (runtime),
  org.jetbrains.kotlin:kotlin-stdlib:2.4.20 (compile), org.springframework.boot:spring-boot-starter-actuator:4.1.1
  (compile), org.springframework:spring-web:7.0.9 (compile), tools.jackson.core:jackson-databind:3.2.3 (compile),
  tools.jackson.module:jackson-module-kotlin:3.2.3 (runtime). The classes are unchanged.

## [1.0.0] - 2026-10-12
### Added
- `product-runtime-api`: the product's configuration values (INV-T-2876, INV-MOD-2745) — `ReplicaFeeds`
  (OrganisationStructureChanged and EntityProfileChanged feed the structure replica with tenancy-svc as its
  fallback, AuthorityChanged the authority replica with access-svc, PolicyChanged the policy replica; AD-26),
  `PlatformEventCatalogue` (the nine platform events of INV-REQ-1226 b1) and `TrainFence` (rules-svc arms, the
  pack holders present in the train acknowledge; INV-REQ-1166 b2).
- `product-runtime`: `ProductRuntimeConfiguration` (TPL-18), imported after `ServiceRuntimeConfiguration` or
  auto-configured, relying on no ordering (service-runtime 3.2.1 or later, INV-T-3881, INV-Q-3877), wiring:
  - `GstVariationPointCatalogue`, config-resolver's `VariationPointCatalogue` over gst-catalogue 1.14.0's
    `VariationPoint`: every row mapped unchanged, typed values and bounds, acknowledgers one per role,
    constraint records as `crossOption`, the cross-scope and installation flags, `keys()` from the owner
    (INV-Q-2934, INV-Q-3119, INV-Q-3134, INV-Q-3167, INV-Q-3171, INV-Q-3173, INV-Q-3854);
  - `AuthorityDelegationProbe`, config-resolver's `DelegationProbe` over the authority replica (INV-Q-3136);
  - `StructureFeedHandler`, `AuthorityFeedHandler` and `PolicyFeed`, and the `TenancyStructureFallback` and
    `AccessAuthorityFallback` service-runtime guards with each edge's policy (INV-Q-3259). A structure event
    without `node` is a ContractViolation and the replica keeps the node it holds (INV-Q-3850).
- The event payload types and the tenancy and access clients are generated at build time by platform-build's
  gst.event-types and gst.contract-client from the pinned platform-contracts bundle, and ship inside the
  `product-runtime` artefact (INV-Q-3852).

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
