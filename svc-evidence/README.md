# svc-evidence

`evidence-svc` — Proof — the immutable record of what was submitted, decided and done, retained and producible for years

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

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
