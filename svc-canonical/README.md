# svc-canonical

`canonical-svc` — Document identity and truth — what was recorded, as which logical document, at which version, classified how

**Status:** service skeleton (INV-T-5544): the five architectural modules, the schema `canonical` with its first migration, the contract gate and the acceptance-criterion key gate. No operation is implemented yet (every one answers 501). This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.2](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 3: Document pipeline (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Write-heavy, append-only |

## Owns

source_event; document; document_version; derived line, subtotal and party tables; holds; decision-trace references (the traces themselves are in evidence-svc); the replicated acknowledgement view

## Provides

The record API to ingestion-svc. Document and version query APIs to console-bff. payload-svc receives the canonical version in CanonicalVersionCreated (AD-30)

## Capabilities

Record with idempotency, and assign or bind the Doc UUID in the same transaction (FR-C.1, AD-1, AD-15). Immutable versioning. Derivation steps 1–8 (§20.6), calling the two leaves. The document-level OR rule. Period assignment from the tenancy calendar, and reassignment on a frequency change (FR-AA.6). Entity-status and obligation-start inputs to scope, resolving NOT_SUBMITTABLE (§33.5, §33.7). Correlation of network receipt and AP posting (§33.4). Recording of aggregate documents from AggregateReady, applying the placeholder literals of the MDE pack (§6.7, C-8). Holding and auto-release

## Events published

- CanonicalVersionCreated
- DecisionTraceRecorded
- DocumentHeld
- DocumentReleased
- PeriodReassigned
- TransactionRecorded

## Events consumed

- AggregateReady
- EntityProfileChanged
- FilingFrequencyChanged
- MasterDataResolved
- SubmissionAcknowledged

## Synchronous calls

- masterdata-svc
- rules-svc

## External dependencies

None

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log` |
| L2 | `calendar-and-periods`, `document-lifecycle`, `integration-canon` |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Know the submission type (FR-K.2)
- build XML or know a specification version
- mutate a version in place (FR-D.2)
- remove out-of-scope lines (C-5)
- regenerate a Doc UUID on retry (FR-C.1)
- act as the system of record for decision traces

## Degradation

If rules-svc or masterdata-svc is unavailable, documents hold in INGESTED with ING-016, auto-released on recovery and counted as unsubmitted in period readiness. They never proceed unclassified or defaulted (FR-K.12)

## Build

Layout (TPL-01): `canonical-contract` (server interfaces and DTOs generated from `platform-contracts/canonical/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `canonical-domain`, `canonical-application`, `canonical-adapters` (`db/migration/`, the generated jOOQ code, the 501 controllers), `canonical-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only). The `gst.*` convention plugins come from platform-build's registry at `platformBuild`.

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`).

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `canonical-domain` or `canonical-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key or exactly `UNASSIGNED`, or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key; empty now): the gate also fails when it is missing, holds a non-key line, or lists a key no row names. |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a row whose wave is at most the run wave has no executed test whose name holds the key as a whole token, or a test name holds a key in no row. Every task of this service appends the rows of its own `ac_refs`. |
| Database rules | `canonical-adapters` `SqlRulesTest`, `verifySqlPlacement` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column. |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
