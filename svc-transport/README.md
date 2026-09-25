# svc-transport

`transport-svc` — The Access Point conversation — everything that crosses to and from the AP

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

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
