# platform-contracts

One published source for every cross-service contract

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Kind | Contract artefacts |
| Contains | The event envelope and every event schema, domain and platform (§19.8, FR-AH.15); the error envelope; the `ErrorCode` and `VariationPoint` catalogue artefact of `gst-catalogue`; the API contracts of §19 and every service's published API; the lifecycle states of §33.2 |
| SaaS | Not deployed; consumed at build time and published per train |
| Private | Same |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |
| Design | [SDS §18.14](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |

Also holds the topic definitions of the `event-log` component, which follow from the event schemas.

**Schema placement (pending a design fix).** Schemas are authored here in one directory per owning service, with that service as code owner. SDS FR-AH.13 still says "published from its owner's repository"; the design will be aligned.
