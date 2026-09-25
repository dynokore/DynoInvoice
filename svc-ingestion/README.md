# svc-ingestion

`ingestion-svc` — Inbound integration — getting accounting facts from customer systems into the System, completely

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.1](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 3: Document pipeline (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Horizontal, bursty at batch close |

## Owns

Adapter registry and configuration; feed watermarks; batch manifests; trigger coverage; feed health and completeness counters; API client usage state

## Provides

The public ingestion API — including the control-totals resource (§19.7) — through the gateway, to ingest principals. Feed administration commands to console-bff

## Capabilities

Public ingestion API: transactions, batches, aggregates, issuances, pre-issuance checks (§19, §33.1). API lifecycle — versions, deprecation, protective rate limits, error envelope, test mode (§35.7). Adapters and scheduled pulls with watermark and look-back (FR-A.3). Batch manifest completeness (FR-M.8). Trigger-coverage registry and waivers (FR-A.1, FR-AB.6). Feed health and replay by window. Hand-off of network-received purchase documents into recording (§33.4)

## Events published

- FeedHealthChanged

## Events consumed

- NetworkDocumentReceived
- TransactionRecorded

## Synchronous calls

- aggregation-svc
- canonical-svc
- payload-svc

## External dependencies

Upstream ERP, POS and expense systems — pull feeds and file drops (SRS EI-5 to EI-7)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Persist document facts or the idempotency record (FR-Z.4)
- decide scope or assign tax codes (FR-E.6)
- decide between resubmission and credit note (FR-A.7)
- send any outbound message or webhook (§28.17)
- reject compliance data because of a plan limit (AD-14)

## Degradation

Unavailable: callers retry, and the idempotency pair makes retry safe (FR-M.3). A feed stall is detected by the completeness controls and blocks period readiness (FR-A.5) — the most dangerous failure in the system, because it is silent (FR-F.6)
