# svc-masterdata

`masterdata-svc` — Parties and external reference data — who the parties are, and the external reference facts the System relies on, each with provenance

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.3](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Read-heavy, cacheable |

## Owns

Identity triples; counterparties and identifiers with provenance; directory cache; register lookup cache; exchange-rate tables; counterparty requests, with the counterparty contact's e-mail and optional mobile number (FR-AG.70); import history

## Provides

Resolution API to canonical-svc. Register-fact and rate lookup API to payload-svc. Remediation, import and request commands to console-bff

## Capabilities

Identity-triple resolution by entity and constitution (FR-1.1–1.3). Counterparty resolution and the hold pattern (§4.3.2). Provenance (FR-1.5). Gap report, bulk remediation and counterparty import (FR-1.7, FR-1.8, §35.10). Counterparty self-service requests (§34.9). Sole client of the external reference registries: SG Peppol Directory and SMP, the IRAS Check GST Register, and exchange-rate sources — lookups, caches, connections (§35.2, §35.13, AD-23)

## Events published

- EvidenceArtefactRecorded
- MasterDataResolved
- ReferenceRateLoaded
- SignedLinkRequested

## Events consumed

- EntityProfileChanged

## Synchronous calls

None

## External dependencies

SG Peppol Directory and SMP; IRAS Check GST Register; exchange-rate sources (AD-23)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `adapter-runtime` |
| L2 | `party-registry` |
| L3 | `product-runtime`, `gst-catalogue`, Check GST Register adapter, Peppol directory client |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Call any pipeline service — it is a leaf (FR-K.2)
- validate counterparty-supplied data as a precondition (FR-1.5)
- block issuance on a directory outage (FR-E.7)
- hard-code supplier_gstn = entity.gstn (FR-1.2)
- decide whether a GST charge is wrongful — it returns register facts, and the rule belongs to payload-svc

## Degradation

Directory outage degrades to off-network, Type 2, which is fully compliant (FR-1.10). Register outage returns REGISTER_UNAVAILABLE (FR-AE.4). A missing rate leaves the document held in payload-svc (PAY-013)
