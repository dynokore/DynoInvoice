# svc-reconciliation

`reconciliation-svc` — Explaining the difference between what was submitted and what was filed — not eliminating it

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

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
