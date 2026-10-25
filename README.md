# DynoInvoice

> **Built around the clock by AI coding agents.** Agents work on DynoInvoice
> day and night, through the night and over weekends, merges included. This
> mirror syncs every hour, so you can
> [watch their commits land](https://github.com/dynokore/DynoInvoice/commits/main).

DynoInvoice is an e-invoicing and GST compliance platform for Singapore, built
on InvoiceNow (Peppol). It sits beside a business's existing ERP, billing,
point-of-sale and expense systems, and handles what the GST InvoiceNow
requirement asks of a GST-registered business: deciding which transactions are
in scope, mapping tax codes to IRAS GST categories, building and validating the
required invoice data, submitting it to IRAS through an IMDA-accredited Access
Point, and reconciling submissions against GST returns. Accounting firms and
tax agents can act for clients with each client's consent for each entity. It
runs as a multi-tenant service or a single-tenant private deployment from the
same code. It doesn't run its own Access Point, prepare GST returns, or replace
accounts receivable and payable.

We intend to bring DynoInvoice to market when it's ready. It is also our proof
for DynoKore Keel: it is developed by AI coding agents under human direction.
This repository is **source-available**: published for reading and study, not
as an open-source project. Development is ongoing: agents commit continuously,
and incomplete features are expected. More:
https://dynokore.com/blog/launch-dynoinvoice/

## Architecture

![DynoInvoice architecture](https://dynokore.com/blog/launch-dynoinvoice/architecture.png)

Solid boxes have code on main and are still in progress; dashed boxes are
designed but not built yet.

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
- **Traceability.** Every change traces back to a task, its requirements and
  acceptance criteria, the review and any human decision, all recorded in
  Keel. A public view of this record is being built; until then,
  [How DynoInvoice is built, commit by commit](https://dynokore.com/work/dynoinvoice/traces/)
  shows how to read a commit and traces eight examples from requirement to
  merged code.

## Building

Builds depend on internal build tooling that is not published. This repository
is published for reading and study; it is not intended to be built or run
outside DynoKore.

## Read-only

This repository is a one-way mirror of the main branch after merge and green
CI. Issues, pull requests and the wiki are disabled, and pull requests are
closed automatically. Discussions are open for questions about how the system
was built.

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
