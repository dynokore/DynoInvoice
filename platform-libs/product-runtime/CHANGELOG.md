# Changelog

All notable changes to `product-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [2.0.7] - 2026-10-30
### Changed
- Metadata-only change (INV-T-13295, G-DVS): depends on `service-runtime 10.24.0`, whose version steps; no code change.

## [2.0.6] - 2026-10-30
### Changed
- Metadata-only change (INV-T-12602, G-DVS): depends on `service-runtime 10.23.0`, whose version steps; no code change.

## [2.0.5] - 2026-10-29
### Changed
- Metadata-only change (INV-T-12071, G-DVS): depends on `service-runtime 10.22.0`, whose version steps; no code change.

## [2.0.4] - 2026-10-29
### Changed
- Metadata-only change (INV-T-12639, G-DVS): depends on `outbox-and-queue 6.7.0`, whose version steps; no code change.

## [2.0.3] - 2026-10-29
### Changed
- Metadata-only release (INV-T-11911, G-DVS): depends on `service-runtime 10.21.0`, whose published module metadata changed; no code change.

## [2.0.2] - 2026-10-28
### Changed
- Metadata-only release (INV-T-12057, G-DVS): depends on `service-runtime 10.20.0`, whose published module metadata changed; no code change.

## [2.0.1] - 2026-10-28
### Changed
- Metadata-only release (INV-T-10969, G-DVS): depends on `service-runtime 10.19.0`, whose published module metadata changed; no code change.

## [2.0.0] - 2026-10-28
### Changed
- Requires `gst-catalogue` 2.8.0 or later as the new minimum (pinned to 2.11.0): `VariationPoint.setBy` is removed in the catalogue (2.0.0), so `GstVariationPointCatalogue` now maps `setBy` of a definition from `OperationRoles.rolesFor(form, vp.setPermission)`, the union of the role keys over every `DeploymentForm`, PERMITTED and WITHIN_SCOPE cells alike (INV-T-11572; prerequisite of INV-T-9865).

## [1.6.5] - 2026-10-28
### Changed
- Metadata-only release (INV-T-11067, INV-Q-11058, G-DVS): depends on `service-runtime 10.18.0`, whose published module metadata changed; no code change.

## [1.6.4] - 2026-10-27
### Changed
- Metadata-only change (INV-T-9100, G-DVS): depends on `outbox-and-queue 6.6.0`, whose version steps; no code change.

## [1.6.3] - 2026-10-27
### Changed
- Depends on `service-runtime 10.17.0`: `AuthorityReplica.decideTenant` is added in `service-runtime` (INV-T-11703; G-DVS).

## [1.6.2] - 2026-10-27
### Changed
- Depends on `service-runtime 10.16.0`: the machine access token is verified in `service-runtime` (INV-T-9155; G-DVS).

## [1.6.1] - 2026-10-27
### Changed
- Depends on `service-runtime 10.15.0`: `IngestionErrorCode` gains ING-002 and ING-022 (INV-T-11270; G-DVS).

## [1.6.0] - 2026-10-27
### Changed
- Depends on `service-runtime 10.14.0`: the property `service-runtime.modules.audit.writer` (INV-T-11437).

## [1.5.0] - 2026-10-27
### Changed
- Depends on `service-runtime 10.13.0`: `EntityRefScopeFilter` judges list-valued entity references (INV-T-9632).

## [1.4.4] - 2026-10-27
### Changed
- Metadata-only release (INV-T-9058, G-DVS): depends on `service-runtime 10.12.0`, whose published bytes changed; no code change.

## [1.4.3] - 2026-10-27
### Changed
- Metadata-only release (INV-T-9057, G-DVS): depends on `service-runtime 10.11.0`, whose published bytes changed; no code change.

## [1.4.2] - 2026-10-26
### Changed
- Metadata-only release (INV-T-11066, INV-Q-11056, G-DVS): depends on `tenancy-kernel 4.2.0`, whose published module metadata changed; no code change.

## [1.4.1] - 2026-10-26
### Changed
- Metadata-only release (INV-Q-10832, INV-Q-10848, INV-T-10836, G-DVS): depends on `tenancy-kernel 4.1.1`, `audit-log 4.0.12`, `config-resolver 5.0.9`, `outbox-and-queue 6.5.1` and `rulepack-kernel 4.1.1`, whose published bytes changed; no code or API change.

## [1.4.0] - 2026-10-25
### Added
- `PlatformEventCatalogue.AUTHORITY_CHANGED` (`gst.platform.authority-changed.v1`), last in `ALL`; the authority replica feed and `AuthorityFeedHandler.decode` take their type and topic from it and refuse `com.dynokore.gst.access.AuthorityChanged.v1`. `ProductEventTypes.AUTHORITY_CHANGED` and `AUTHORITY_CHANGED_TOPIC` stay, deprecated at level ERROR (INV-Q-9229, INV-T-9356). Needs `outbox-and-queue 6.5.0`.

## [1.3.5] - 2026-10-25
### Changed
- Metadata-only release (INV-T-9154, G-DVS): depends on `service-runtime 10.10.0`, whose published module metadata changed; no code change.

## [1.3.4] - 2026-10-24
### Changed
- Metadata-only release (INV-T-9055, G-DVS): depends on `outbox-and-queue 6.4.0`, whose published module metadata changed; no code change.

## [1.3.3] - 2026-10-24
### Changed
- Metadata-only release (INV-T-9056, G-DVS): depends on `service-runtime 10.9.0`, whose published module metadata changed; no code change.

## [1.3.2] - 2026-10-23
### Changed
- Metadata-only release (INV-T-8921, G-DVS): depends on `service-runtime 10.8.0`, whose published module metadata changed; no code change.

## [1.3.1] - 2026-10-23
### Changed
- Metadata-only release (INV-T-8800, G-DVS): depends on `service-runtime 10.7.0`, whose published module metadata changed; no code change.

## [1.3.0] - 2026-10-23
### Added
- `PlatformEventCatalogue` gains `BREAK_GLASS_ALERT_RAISED` (`gst.platform.break-glass-alert-raised.v1`), the twelve platform events of INV-REQ-1226 (INV-Q-8839, INV-T-8826).

## [1.2.7] - 2026-10-22
### Changed
- Metadata-only release (INV-T-8522, G-DVS): depends on `service-runtime 10.6.0`, whose published module metadata changed; no code change.

## [1.2.6] - 2026-10-22
### Changed
- Metadata-only release (INV-T-8253, G-DVS): depends on `service-runtime 10.5.0`, whose published module metadata changed; no code change.

## [1.2.5] - 2026-10-22
### Changed
- Metadata-only release (INV-T-8198, G-DVS): depends on `service-runtime 10.4.0`, whose published module metadata changed; no code change.

## [1.2.4] - 2026-10-21
### Changed
- Metadata-only release (INV-T-7937, G-DVS): depends on `service-runtime 10.3.1`, whose published module metadata changed; no code change.

## [1.2.3] - 2026-10-21
### Changed
- Metadata-only release (INV-T-7895, G-DVS): depends on `service-runtime 10.3.0`, whose published module metadata changed; no code change.

## [1.2.2] - 2026-10-20
### Changed
- Metadata-only release (INV-T-7520, G-DVS): depends on `service-runtime 10.2.0`, whose published module metadata changed; no code change.

## [1.2.1] - 2026-10-20
### Changed
- Metadata-only release (INV-T-7557, G-DVS): depends on `service-runtime` 10.1.0, whose published module metadata changed; no code change.

## [1.2.0] - 2026-10-20
### Changed
- `gst-catalogue` pin raised from 1.14.0 to 1.28.0, whose POM declares the company licence (INV-Q-7529, INV-T-7531); the POMs of 1.14.0 and 1.15.0 declare none and cannot be corrected. Locks and verification metadata regenerated; no code change.

## [1.1.1] - 2026-10-19
### Changed
- detekt type-resolution findings fixed (platform-build 0.21.0, INV-Q-4629); no behaviour or API change

## [1.1.0] - 2026-10-19
### Added
- `PlatformEventCatalogue` gains `PrivacyNoticeDue` and `AccreditationAlertRaised`, the eleven platform events of INV-REQ-1226 (INV-Q-7336, INV-T-7341).

## [1.0.31] - 2026-10-19
### Changed
- Metadata-only release (INV-Q-5337, G-DVS): depends on `outbox-and-queue` 6.1.6 and `service-runtime` 10.0.0, whose published module metadata changed; no code change.

## [1.0.30] - 2026-10-19
### Changed
- Metadata-only release (INV-T-7098, INV-Q-5337, G-DVS): depends on `outbox-and-queue` 6.1.5 and `service-runtime` 9.2.1, whose published module metadata changed; no code change.

## [1.0.29] - 2026-10-19
### Changed
- Metadata-only release (INV-T-6974, G-DVS): depends on `service-runtime` 9.2.0, whose published module metadata changed; no code change.

## [1.0.28] - 2026-10-19
### Changed
- Metadata-only release (INV-T-5362, G-DVS): depends on `config-resolver` 5.0.0, whose published module metadata changed; no code change.

## [1.0.27] - 2026-10-19
### Changed
- Metadata-only release (INV-T-5363, G-DVS): depends on `outbox-and-queue` 6.1.4, whose published module metadata changed; no code change.

## [1.0.26] - 2026-10-18
### Changed
- Metadata-only release (INV-T-4224, G-DVS): depends on `tenancy-jooq` 1.3.0, whose published module metadata changed; no code change.

## [1.0.25] - 2026-10-19
### Changed
- Metadata-only release (INV-T-6757, G-DVS): depends on `service-runtime` 9.0.0, whose published module metadata changed; no code change.

## [1.0.24] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5365, G-DVS): depends on `service-runtime` 8.3.1, whose published module metadata changed; no code change.

## [1.0.23] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5360, G-DVS): depends on `service-runtime` 8.3.0, whose published module metadata changed; no code change.

## [1.0.22] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6538, G-DVS): depends on `service-runtime` 8.2.0 and `config-resolver` 4.1.0, whose published module metadata changed; no code change.

## [1.0.21] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5361, G-DVS): depends on `audit-log` 4.0.0, whose published module metadata changed; no code change.
- Also depends on a changed upstream release (INV-T-5365, G-DVS): `service-runtime` 8.1.9, whose published module metadata changed; no code change.

## [1.0.20] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [1.0.19] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6393, G-DVS): depends on `service-runtime` 8.1.7; no code change.

## [1.0.18] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6291, G-DVS): depends on `outbox-and-queue` 6.1.0 and `service-runtime` 8.1.6; no code change.

## [1.0.17] - 2026-10-18
### Changed
- Metadata-only release (INV-T-4202, G-DVS): depends on `outbox-and-queue` 6.0.1 and `service-runtime` 8.1.5; no code change.

## [1.0.16] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5306, G-DVS): depends on `service-runtime` 8.1.4 and `outbox-and-queue` 6.0.0; no code change.

## [1.0.15] - 2026-10-18
### Changed
- Metadata-only release (INV-T-6135, G-DVS): depends on `service-runtime` 8.1.3, whose published module metadata changed; no code change.

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
