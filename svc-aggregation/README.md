# svc-aggregation

`aggregation-svc` — High-volume transaction classes — POS, STI and PCP transactions collapsed into aggregate documents

**Status:** slice 1 of 4 (INV-T-8451) built: skeleton, gates, intake type check. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.5](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Periodic, high fan-in |

## Owns

Windows, batches and numbering sequences; underlying transaction lists retained for audit

## Provides

The aggregate intake API to ingestion-svc. Drill-down query to console-bff

## Capabilities

Accept POS, STI and PCP transactions from ingestion-svc (FR-4.5): transaction-level feeds through the batch channel, and pre-aggregated calls whose detail arrives through the batch channel (§19.6). Close windows on the configured cadence, split at period boundaries. One line per category-code group. Refund and supplementary aggregates. STI exclusion by receipt number. Deterministic numbering. Retain the underlying detail, minimised (§33.9, §33.14). Hand every closed aggregate to canonical-svc for recording (AD-22)

## Events published

- AggregateReady
- AggregateWindowChanged

## Events consumed

- SubmissionDespatched

## Synchronous calls

None

## External dependencies

None

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Aggregate anything outside POS, STI and PCP (FR-4.5)
- apply placeholder literals — canonical-svc applies them from the MDE pack, and payload-svc validates them (C-8)
- amend a transmitted aggregate
- aggregate across a period boundary
- discard the underlying detail (FR-4.9)
- bypass canonical-svc

## Degradation

A missed window is a completeness gap, surfaced through period readiness by AggregateWindowChanged and through the job console (§35.12), never silently deferred

## Build

Layout (TPL-01): `aggregation-contract` (server interfaces and DTOs generated from `platform-contracts/aggregation/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `aggregation-domain`, `aggregation-application`, `aggregation-adapters` (`db/migration/`, the generated jOOQ code, the 501 controllers, the ING-006 advice), `aggregation-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only). The `gst.*` convention plugins come from platform-build's registry at `platformBuild`.

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`).

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `aggregation-domain` or `aggregation-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key or exactly `UNASSIGNED`, a row names another task than the split sets, or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key; empty now). |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a wave 2 row has no executed test whose name holds the key as a whole token, a test name holds a key in no row, or the rows of an owned slice differ from its `ac_refs`. Every task of this service appends the rows of its own `ac_refs` and deferred criteria. |
| Database rules | `aggregation-adapters` `SqlRulesTest`, `verifySqlPlacement`, `TenantTablesTest` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column; a table without forced row level security and the signed binding policy. |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
