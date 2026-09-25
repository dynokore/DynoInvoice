# svc-control-plane

`control-plane-svc` — Authoring and releasing what deployments execute

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.12](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas |
| Scaling | Operator-hosted only |

## Owns

Authoring workspace; signing key references; release history; plans and entitlement definitions; licences; tenant provisioning requests; received health telemetry; its own audit entries and job records, written through audit-log into the controlplane schema

## Provides

The signed pack feed, read by rules-svc through PackSource (AD-31). The operator console for regulatory, release and commercial roles

## Capabilities

Regulatory pack authoring, signing and publication, both as feed and as bundle (FR-H.5). Release-train and patch-train publication (§34.13). Plans, entitlement definitions, and licence issuance and renewal (§34.11). Tenant provisioning in the SaaS form. Receipt of optional anonymised health telemetry

## Events published

None

## Events consumed

None

## Synchronous calls

- tenancy-svc

## External dependencies

None; its signing keys are read through SecretStore

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `config-resolver`, `audit-log`, `rulepack-kernel` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Be deployed into a private installation (FR-J.3)
- receive tenant data from any data plane (§12.2)
- be a runtime dependency of submission (FR-H.3)

## Degradation

Unavailable: no new packs are distributed and no train is published. Every data plane continues on what it holds
