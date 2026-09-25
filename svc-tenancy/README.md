# svc-tenancy

`tenancy-svc` — Organisation structure and each entity's regulatory profile — the "structure" class of FR-Z.13

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.11](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Read-heavy, cacheable |

## Owns

Tenants, groups, entities and business units; entity profiles and their history; period records and extensions; workspaces; certification status; entitlement and metering counters; licence status; questionnaire answers and the applied operating profile (FR-AI.7, FR-AI.8); installation facts — release train and supported window, backup-target verification (FR-H.23), installation platform (VP-52), heartbeat target (FR-AE.70)

## Provides

Structure queries that fill replica cache misses (FR-AH.4). Profile, calendar and workspace commands to console-bff. Tenant provisioning and usage counters for billing (FR-G.11) to control-plane-svc (SaaS)

## Capabilities

The four-level hierarchy (FR-G.1). Entity lifecycle: create, go-live gate (FR-Q.16), suspend, deregister, offboarding checks (FR-AB.9). The entity profile, effective-dated: constitution, registration period, filing-frequency history, obligation start, Excluded Business and partial-exemption status, elections, issuance mode, rendition responsibility (§33.1, §33.5, §33.7, §35.4). Phase assessment. Period calendar generation, filing due dates under the calendar pack and the non-business-day rule (VP-62), and recorded extensions (§35.3). Workspaces and their pairing (§35.5). Certification status (§35.8). Entitlement, metering and licence status (FR-G.11, FR-G.12)

## Events published

- EntityProfileChanged
- FilingFrequencyChanged
- InstallationStatusChanged
- OrganisationStructureChanged
- PeriodDueDateChanged
- PeriodExtended

## Events consumed

- RulePackActivated
- RulePackStaged
- SubmissionAcknowledged

## Synchronous calls

None

## External dependencies

Licence file or entitlement source (LicenceCheck)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `rulepack-kernel`, `adapter-runtime` |
| L2 | `calendar-and-periods` |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Hold grants, consents, principals or any other authority data (AD-20)
- evaluate authorisation
- block submission on an entitlement limit or an expired licence (FR-G.12, FR-H.4)
- compute a submission due date — it records period dates and extensions, and submission-svc recomputes

## Degradation

Cached structure continues to serve (§18.6)
