# DynoInvoice

DynoInvoice is an e-invoicing and GST compliance platform for Singapore (IRAS
InvoiceNow / Peppol), built as Kotlin microservices. It is developed by AI
coding agents under human direction, as a case study in agent-built enterprise
software. This repository is **source-available**: it is published for reading
and study, not as an open-source project.

## How it is built

- **People decide.** The project owner rules on functionality, development
  direction, exemptions and relaxations. Every requirement, acceptance
  criterion and design question is recorded before code is written.
- **Agents do the work.** Coding, review, requirement breakdown and
  examination are done by separate AI agents. A coder never reviews its own
  merge request. Changes merge only after an independent review passes and CI
  is green. Commits by agents are authored as `DynoKore Agent` and carry a
  `Co-Authored-By: Claude` line; commits by people are authored as
  `DynoKore Team`.
- **Traceability.** For any commit, the task, requirements, acceptance
  criteria, test evidence, review result and human approvals behind it can be
  looked up at [dynokore.dev](https://dynokore.dev).

## Building

Builds depend on internal build tooling that is not published. This repository
is published for reading and study; it is not intended to be built or run
outside DynoKore. CI results and test reports are published at
[dynokore.dev](https://dynokore.dev).

## Read-only

This repository is a one-way mirror of the main branch after merge and green
CI. Issues, pull requests, wiki and discussions are disabled; pull requests are
closed automatically.

## Contents

| Directory | What it is |
|---|---|
| [gateway-authz](gateway-authz/) | Token and revocation validator in the API gateway (extAuth). |
| [platform-contracts](platform-contracts/) | One published source for every cross-service contract (OpenAPI, events, catalogue). |
| [platform-libs](platform-libs/) | Shared platform modules used by every service. |
| [svc-access](svc-access/) | access-svc: who may do what, on whose authority (grants, authorisation). |
| [svc-aggregation](svc-aggregation/) | aggregation-svc: high-volume transactions collapsed into aggregate documents. |
| [svc-canonical](svc-canonical/) | canonical-svc: document identity, versions and classification. |
| [svc-console-bff](svc-console-bff/) | console-bff: the operator console's backend. |
| [svc-control-plane](svc-control-plane/) | control-plane-svc: authoring and releasing what deployments execute. |
| [svc-evidence](svc-evidence/) | evidence-svc: the immutable record of what was submitted, decided and done. |
| [svc-ingestion](svc-ingestion/) | ingestion-svc: getting accounting facts from customer systems in, completely. |
| [svc-masterdata](svc-masterdata/) | masterdata-svc: parties and external reference data, with provenance. |
| [svc-notification](svc-notification/) | notification-svc: alerts, messages and webhooks. |
| [svc-payload](svc-payload/) | payload-svc: transmitted and rendered forms of a document and their validation. |
| [svc-reconciliation](svc-reconciliation/) | reconciliation-svc: explaining submitted versus filed differences. |
| [svc-rules](svc-rules/) | rules-svc: tax rules as versioned data, applied reproducibly. |
| [svc-submission](svc-submission/) | submission-svc: obligations and their transmission to IRAS. |
| [svc-tenancy](svc-tenancy/) | tenancy-svc: organisation structure and each entity's regulatory profile. |
| [svc-transport](svc-transport/) | transport-svc: the conversation with the Peppol Access Point. |

Some services are still at an early stage and contain only a README.

## Licence

[PolyForm Strict License 1.0.0](LICENSE.md): non-commercial use only; no
distribution of modified versions. See [NOTICE](NOTICE).

More about the project: [dynokore.com](https://dynokore.com).
