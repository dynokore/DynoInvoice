# svc-payload

`payload-svc` — Transmitted and rendered forms of a document — projections and their validation

**Status:** service skeleton (INV-T-5966): the five architectural modules, the schema `payload` with its first migration, the capability registry and its `markSpecified(SERIALISATION)` guard (INV-REQ-481), the contract gate and the acceptance-criterion key gate. No operation is implemented yet (every one answers 501). This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.6](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 3: Document pipeline (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | CPU-bound, stateless |

## Owns

Built payload index; local compiled-artefact cache (§27.4); the canonical input of each version it built, taken from CanonicalVersionCreated, for byte-identical regeneration (§6.8); sample-check records

## Provides

The validation and pre-issuance API to ingestion-svc. Payload, rendition and regeneration queries to console-bff

## Capabilities

Specification version selected by submission date within the acceptance calendar (§33.8). Customization and Profile ID selection. Projection build. Both validation layers with the severity override map (§6.3, FR-W.8). Scenario handler effects on the payload (§6.6): populating IBT-022 and the UEN and GSTN fields, and building the payload for the self-billing submission type it is given; the decision is rules-svc's (§28.4, FR-AE.89; split by owner decision 25 Sep 2026). Placeholder validation (PAY-008). The wrongful-GST rule, applied to register facts from masterdata-svc. Exchange-rate validation against masterdata-svc rates. Pre-issuance checks and Relay issuance validation (§33.1). The amendment payload difference and its classification against the taxonomy (§33.3). Rendition sample checks (§35.4; rendition in owned mode was withdrawn on 24 Sep 2026). Byte-identical regeneration (§6.8)

## Events published

- EvidenceArtefactRecorded
- PayloadReady
- PayloadRejected

## Events consumed

- CanonicalVersionCreated
- ReferenceRateLoaded
- RulePackActivated
- RulePackStaged

## Synchronous calls

- masterdata-svc
- rules-svc

## External dependencies

None

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `rulepack-kernel` |
| L2 | `document-lifecycle`, `integration-canon` |
| L3 | `product-runtime`, `gst-catalogue`, PINT-SG and SG Peppol BIS projections, MDE sets, scheme scenario handlers |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Treat a Schematron pass as compliance (FR-V.3)
- use the billing profile for a purchase (FR-5.3)
- recompute totals (FR-M.7)
- enforce exact line-to-total equality (FR-7.5)
- read rules-svc's store (FR-Z.10)
- call an external registry directly (AD-23)
- decide the consequence of an amendment — it classifies, and submission-svc decides (FR-A.7)
- block issuance when the register is unavailable (FR-7.8)

## Degradation

Register unavailable: proceed with REGISTER_UNAVAILABLE and an asynchronous re-check. A validation failure holds the document and is never downgraded to let it through

## Build

Layout (TPL-01): `payload-contract` (server interfaces and DTOs generated from `platform-contracts/payload/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `payload-domain`, `payload-application`, `payload-adapters` (`db/migration/`, the generated jOOQ code, the 501 controllers), `payload-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only). The `gst.*` convention plugins come from platform-build's registry at `platformBuild`.

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`).

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `payload-domain` or `payload-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key or exactly `UNASSIGNED`, or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key; empty now): the gate also fails when it is missing, holds a non-key line, or lists a key no row names. |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a wave 3 row has no executed test whose name holds the key as a whole token, or a test name holds a key in no row. Every task of this service appends the rows of its own `ac_refs`. |
| Database rules | `payload-adapters` `SqlRulesTest`, `verifySqlPlacement` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column. |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
