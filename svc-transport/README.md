# svc-transport

`transport-svc` — The Access Point conversation — everything that crosses to and from the AP

**Status:** service skeleton and the submit path (INV-T-4981): the five architectural modules, the schema `transport` with its first migration, the `submitPayload` use case behind the Access Point adapter port, the attempt log, the throughput ceiling rule, the failure metrics, the contract gate and the acceptance-criterion key gate. `submitPayload` is not yet exposed over HTTP (it answers 501): see "Open gaps". Every other operation answers 501 until the task named for it in `operations.tsv` implements it. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.8](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | I/O-bound, rate-limited |

## Owns

Transport attempt log; AP endpoint configuration; notification de-duplication ledger. Credentials live in the vault, never in the schema

## Provides

The submit and send API to submission-svc. The notification endpoint, through the gateway, to ap-callback principals

## Capabilities

AP adapters, two concurrently during migration (FR-E.2). Submission of payloads to IRAS through the AP. sendNetworkDocument for Relay-mode Type 1A (§33.1). The AP notification endpoint, with polling where the AP offers only that: acknowledgements, delivery status, inbound network documents (§33.6). Credentials through the vault. AP error translation. Transport retry. AP connection management (§35.13)

## Events published

- AcknowledgementReceived
- NetworkDocumentReceived

## Events consumed

None

## Synchronous calls

None

## External dependencies

The Access Point, and through it IRAS and the Peppol network

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue`, IRAS API adapter |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Decide which document to send, when, or under which Doc UUID (FR-E.1)
- compute deadlines
- act as system of record for acknowledgements
- let AP specifics appear in its interface
- log credentials
- interpret the content of an inbound document

## Degradation

Unavailable: submissions queue upstream; failover to a secondary adapter where configured

## Build

Layout (TPL-01): `transport-contract` (server interfaces and DTOs generated from `platform-contracts/transport/openapi.yaml`, pinned by `platformContractsBundle` in `gradle/libs.versions.toml`), `transport-domain` (outcomes, ids, the throughput ceiling rule), `transport-application` (the Access Point port, the attempt log port, `SubmitPayload`), `transport-adapters` (`db/migration/`, the generated jOOQ code, the HTTP Access Point adapter, the metrics, the 501 controllers), `transport-bootstrap` (TPL-18 composition, `application.yaml`) and `acceptance` (test-only).

Gradle runs in the CI image; the build needs Docker (jOOQ code generation and the database tests start `postgres:18`). The boundary tests use the certification-kit Access Point simulator as the adapter under test: build its image with `gradle :simulators:access-point:bootBuildImage` in certification-kit (it is tagged `certkit/access-point-simulator:dev` in the local Docker daemon); a missing image fails the tests.

## Access Point adapter configuration (INV-REQ-501, INV-REQ-539)

`transport.ap.environment` (`SANDBOX` or `PRODUCTION`), `transport.ap.sandbox-endpoint`, `transport.ap.production-endpoint`, `transport.ap.timeout`, `transport.ap.metrics-window` and the adapter list `transport.ap.adapters[n].id` / `.path`. There is no default environment or endpoint. A sandbox configuration resolves only the sandbox endpoint.

## Gates

| Gate | Where | Fails when |
| --- | --- | --- |
| Layer rule (ARC-03) | `acceptance` `ArchitectureTest` | `transport-domain` or `transport-application` imports Spring, jOOQ, Jackson, servlet or JPA types. `java.sql` and `javax.sql` types may be named (owner answer INV-Q-5331 (D)). |
| Contract gate | `operations.tsv`, `acceptance` `OperationsGateTest` | An operationId or channel of the contract is missing from the table, the task cell is not an `INV-T-<n>` key, or is `UNASSIGNED` for an id other than submitPackage, sendNetworkDocument and the scaffold channel (INV-Q-8045), or a done task still answers 501. The done set is `done-tasks.txt` (one INV-T key per line; each task's MR adds its key). |
| Submit definition | `acceptance` `SubmitPayloadContractTest` | A property name reachable from the `submitPayload` request or 202 response is outside the reviewed allowlist, or the outcome enum is not the three outcomes. |
| Key gate | `ac-keys.tsv`, Gradle task `acKeyCoverage` | The table is missing or empty, a wave 2 row has no executed test whose name holds the key as a whole token, or a test name holds a key in no row. Every task of this service appends the rows of its own `ac_refs`. |
| Database rules | `transport-adapters` `SqlRulesTest`, `TenantTablesTest`, `verifySqlPlacement` | Raw SQL, JdbcTemplate, statement creation or a connection from a DataSource; a migration table without a not null tenant column; a table without forced row level security and the signed-binding policy. |
| Dependency integrity (STK-72) | `gradle/verification-metadata.xml`, lock files | A dependency without a checksum or signature entry, or outside the lock files. |

## Open gaps (INV-T-4981)

See the merge request description: the `submitPayload` request of the published contract carries `adapterRef` and `payloadRef` and its response has no acknowledgement field, which the task text and `SubmitPayloadContractTest` do not allow.

## Dependency locking and verification

Refresh after a dependency change, with an empty Gradle user home (see platform-libs README, "Dependency locking and verification"):

```sh
gradle resolveAndLockAll check bootJar -x test -x acKeyCoverage --continue \
  --write-locks --write-verification-metadata sha256,pgp --export-keys --no-configuration-cache
```

Never pass `--dependency-verification off`; CI fails a build that does.
