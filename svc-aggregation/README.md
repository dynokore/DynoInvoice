# svc-aggregation

`aggregation-svc` — High-volume transaction classes — POS, STI and PCP transactions collapsed into aggregate documents

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

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
