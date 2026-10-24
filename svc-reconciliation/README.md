# svc-reconciliation

`reconciliation-svc` — Explaining the difference between what was submitted and what was filed — not eliminating it

**Status:** service skeleton (INV-T-5970): the five architectural modules, the schema `reconciliation` and the role `reconciliation` with the first migration, the contract gate and the acceptance-criterion key gate. No operation is implemented yet (every one answers 501) and no table of the read model exists. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.10](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 4: Evidence, reconciliation, notification and console (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Batch, periodic |

## Owns

Filed returns; its read model; reconciliation runs; variance classifications; work-papers

## Provides

Return upload, through the gateway, to filing-workflow principals and to console-bff. Run and work-paper queries

## Capabilities

Filed-return intake and versioning (§33.10, FR-9.16). Its own read model built from events. Box comparison under the return-mapping pack. Variance classification against the taxonomy. The unexplained residual. Work-paper with attestation. Reconciliation data for the reporting principal

## Events published

- GstReturnRecorded
- ReconciliationCompleted

## Events consumed

- CanonicalVersionCreated
- PeriodReassigned
- RulePackActivated
- RulePackStaged
- SubmissionAcknowledged

## Synchronous calls

None

## External dependencies

None

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `rulepack-kernel` |
| L2 | `reconciliation-engine` |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Query canonical or submission directly (FR-K.10, FR-Z.16)
- present a classified legitimate difference as an error (FR-Q.10)
- attempt to eliminate variances (FR-9.6)
- affect submission other than by publishing the filed return

## Degradation

Unavailable: reporting and return intake are unavailable. Submission is untouched

## Build

Layout (TPL-01): `reconciliation-contract` (server interfaces and DTOs generated from `platform-contracts/reconciliation/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `reconciliation-domain`, `reconciliation-application`, `reconciliation-adapters` (`db/migration/`, the generated jOOQ code, the 501 controllers), `reconciliation-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only). The `gst.*` convention plugins come from platform-build's registry at `platformBuild`.

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`).

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `reconciliation-domain` or `reconciliation-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key or exactly `UNASSIGNED`, or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key; empty now): the gate also fails when it is missing, holds a non-key line, or lists a key no row names. |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a wave 4 row has no executed test whose name holds the key as a whole token, or a test name holds a key in no row. Every task of this service appends the rows of its own `ac_refs`. |
| Database rules | `reconciliation-adapters` `SqlRulesTest`, `verifySqlPlacement` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column. |
| Read-model boundary | `reconciliation-adapters` `ReadModelBoundaryTest` | The role `reconciliation` can read a table of schema `canonical`, `submission` or the look-alike `canonical_x`, or holds USAGE on any schema but `reconciliation` (INV-AC-2189, INV-AC-9536). |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
