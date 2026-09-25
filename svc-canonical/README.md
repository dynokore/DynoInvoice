# svc-canonical

`canonical-svc` — Document identity and truth — what was recorded, as which logical document, at which version, classified how

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

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
