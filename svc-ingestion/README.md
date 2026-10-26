# svc-ingestion

`ingestion-svc` — Inbound integration — getting accounting facts from customer systems into the System, completely

**Status:** service skeleton (INV-T-5968): the five architectural modules, the schema `ingestion` with its first migration, the contract gate, the acceptance-criterion key gate and the idempotency-pair mapping onto canonical-svc. No operation is implemented yet (every one answers 501). This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.1](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 3: Document pipeline (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Horizontal, bursty at batch close |

## Owns

Adapter registry and configuration; feed watermarks; batch manifests; trigger coverage; feed health and completeness counters; API client usage state

## Provides

The public ingestion API — including the control-totals resource (§19.7) — through the gateway, to ingest principals. Feed administration commands to console-bff

## Capabilities

Public ingestion API: transactions, batches, aggregates, issuances, pre-issuance checks (§19, §33.1). API lifecycle — versions, deprecation, protective rate limits, error envelope, test mode (§35.7). Adapters and scheduled pulls with watermark and look-back (FR-A.3). Batch manifest completeness (FR-M.8). Trigger-coverage registry and waivers (FR-A.1, FR-AB.6). Feed health and replay by window. Hand-off of network-received purchase documents into recording (§33.4)

## Events published

- FeedHealthChanged

## Events consumed

- NetworkDocumentReceived
- TransactionRecorded

## Synchronous calls

- aggregation-svc
- canonical-svc
- payload-svc

## External dependencies

Upstream ERP, POS and expense systems — pull feeds and file drops (SRS EI-5 to EI-7)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Persist document facts or the idempotency record (FR-Z.4)
- decide scope or assign tax codes (FR-E.6)
- decide between resubmission and credit note (FR-A.7)
- send any outbound message or webhook (§28.17)
- reject compliance data because of a plan limit (AD-14)

## Degradation

Unavailable: callers retry, and the idempotency pair makes retry safe (FR-M.3). A feed stall is detected by the completeness controls and blocks period readiness (FR-A.5) — the most dangerous failure in the system, because it is silent (FR-F.6)

## Build

Layout (TPL-01): `ingestion-contract` (server interfaces and DTOs generated from `platform-contracts/ingestion/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `ingestion-domain`, `ingestion-application`, `ingestion-adapters` (`db/migration/`, the generated jOOQ code, the 501 controllers), `ingestion-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only). The `gst.*` convention plugins come from platform-build's registry at `platformBuild`.

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`).

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `ingestion-domain` or `ingestion-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key or exactly `UNASSIGNED`, or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key; empty now): the gate also fails when it is missing, holds a non-key line, or lists a key no row names. |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a wave 3 row has no executed test whose name holds the key as a whole token, or a test name holds a key in no row. Every task of this service appends the rows of its own `ac_refs`. |
| Database rules | `ingestion-adapters` `SqlRulesTest`, `verifySqlPlacement` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column. |
| No received pairs | `NoReceivedPairsTest` | A table of schema `ingestion` holds a `source_revision` / `sourceRevision` column: the idempotency record is canonical-svc's (INV-REQ-1191). |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
