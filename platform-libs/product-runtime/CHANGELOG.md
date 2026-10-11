# Changelog

All notable changes to `product-runtime` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [1.0.0] - 2026-10-12
### Added
- `product-runtime-api`: the product's configuration values (INV-T-2876, INV-MOD-2745) — `ReplicaFeeds`
  (OrganisationStructureChanged and EntityProfileChanged feed the structure replica with tenancy-svc as its
  fallback, AuthorityChanged the authority replica with access-svc, PolicyChanged the policy replica; AD-26),
  `PlatformEventCatalogue` (the nine platform events of INV-REQ-1226 b1) and `TrainFence` (rules-svc arms, the
  pack holders present in the train acknowledge; INV-REQ-1166 b2).
- `product-runtime`: `ProductRuntimeConfiguration` (TPL-18), an auto-configuration ordered before
  service-runtime's composition root, wiring:
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
