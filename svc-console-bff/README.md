# svc-console-bff

`console-bff` — The operator's view — one backend that reads widely and writes nothing it does not own

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.15](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 4: Evidence, reconciliation, notification and console (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Read-heavy; rebuilt from events |

## Owns

The console schema (§27.3) — read models and the search index only

## Provides

The console API to console-web; the signed-link pages

## Capabilities

Exception read model; portfolio read model; document search index; job and connection status views — all built from events (FR-AF.2, §35.9, §35.12, §35.13). View assembly through bounded query APIs. Routing every command to the owning service. Authorisation on reads (FR-AF.4). Signed-link pages for client signatories and counterparties (§34.8, §34.9). Orchestration of multi-service flows that no pipeline service may orchestrate: configuration export and promotion (§35.5), privacy correction (§33.14), support bundle assembly (FR-H.22), backfill — scope, the pre-submission reconciliation and the tax.approver approval (§24.6, FR-R.5, FR-R.9). The installation settings screen (UI SCR-41) in both forms, each value routed to its owning service

## Events published

None

## Events consumed

- AggregateWindowChanged
- AuthorityChanged
- CanonicalVersionCreated
- ConnectionStatusChanged
- DecisionTraceRecorded
- DocumentHeld
- DocumentReleased
- EntityProfileChanged
- EvidenceIntegrityFailed
- FeedHealthChanged
- InstallationStatusChanged
- JobRunRecorded
- OrganisationStructureChanged
- PackCurrencyChanged
- PayloadRejected
- PeriodDueDateChanged
- PeriodExtended
- PeriodReassigned
- ReadinessChanged
- ReconciliationCompleted
- SubmissionAcknowledged
- SubmissionDespatched
- SubmissionFailed

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

- Write another service's state
- be called by a pipeline service
- be the only authorisation check on a write
- hold invoice content beyond what an open view needs

## Degradation

Unavailable: the console is unavailable; ingestion, submission and every machine interface continue. A stale read model shows its staleness
