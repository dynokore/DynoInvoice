# svc-evidence

`evidence-svc` — Proof — the immutable record of what was submitted, decided and done, retained and producible for years

**Status:** service skeleton (INV-T-5547): the five architectural modules, the schema `evidence` with its first migration (the outbox only; the evidence tables are built by later tasks), the contract gate, the acceptance-criterion key gate and the separability tests. No operation is implemented yet (every one answers 501). This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.9](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 4: Evidence, reconciliation, notification and console (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Storage-heavy, low QPS |

## Owns

Payload index; acknowledgements; decision traces; register lookups; audit log; certification reports; privacy and security cases; integrity results. Payload blobs in object storage under object lock

## Provides

Lookup, export, verification and privacy queries; retention and legal-hold commands — to console-bff, through which the auditor role and other permitted roles use them; the audit-pack resource of §19.7 through the gateway; certification-report intake from the certification runner, as the certification principal (§22.5, §35.8)

## Capabilities

Append-only persistence of every evidence type, including the transmitted payload, taken from PayloadReady and sealed on SubmissionDespatched. System of record for acknowledgements, decision traces and the audit log (AD-24). Hash chaining, routine verification and the integrity report (§35.11). Retention, legal hold and controlled deletion. Audit pack and offboarding export. Enquiry lookups (FR-AA.12–AA.15), marking imported evidence as such (FR-AB.7). Certification reports (§35.8). Privacy case records and subject-access exports (§33.14). Security case records concluding a credential-compromise review, on the same case mechanism (FR-P.10)

## Events published

- EvidenceIntegrityFailed

## Events consumed

- AuditEntryRecorded
- CanonicalVersionCreated
- DecisionTraceRecorded
- EvidenceArtefactRecorded
- PayloadReady
- SubmissionAcknowledged
- SubmissionDespatched

## Synchronous calls

None

## External dependencies

Object storage with object lock (ObjectStore)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `evidence-store`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Update or delete outside the controlled retention operation (FR-D.4)
- share a lifecycle with operational stores (FR-D.1)
- require the platform to interpret an export (FR-G.10)
- embed supporting documents (FR-9.14)
- call a pipeline service — privacy corrections are orchestrated by console-bff

## Degradation

Evidence writes queue in the producers' outboxes. The pipeline is never blocked on evidence, and an acknowledgement is never lost

## Build

Layout (TPL-01): `evidence-contract` (server interfaces and DTOs generated from `platform-contracts/evidence/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `evidence-domain`, `evidence-application`, `evidence-adapters` (`db/migration/`, the generated jOOQ code, the 501 controllers), `evidence-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only). The `gst.*` convention plugins come from platform-build's registry at `platformBuild`.

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`).

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `evidence-domain` or `evidence-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key or exactly `UNASSIGNED`, or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key; empty now): the gate also fails when it is missing, holds a non-key line, or lists a key no row names. |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a wave 4 row has no executed test whose name holds the key as a whole token, or a test name holds a key in no row. Every task of this service appends the rows of its own `ac_refs`. |
| Database rules | `evidence-adapters` `SqlRulesTest`, `verifySqlPlacement` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column. |
| Separability (INV-REQ-636, INV-AC-7871, INV-AC-7872) | `evidence-adapters` `SeparabilityTest`, `SeparableStoreTest` | A foreign key, view, function or trigger of schema `evidence` refers to a schema other than `evidence` or `pg_catalog` (equality of the name), or dropping the operational schema changes a row of an `evidence` table. |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
