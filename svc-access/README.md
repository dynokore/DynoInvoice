# svc-access

`access-svc` — Who may do what, on whose authority — the "authority" class of FR-Z.13

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.16](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | Read-heavy, never cached for authority |

## Owns

Identities, grants and role assignments; principals, with credential references only — secrets stay in the vault; consents, with their scope items (FR-AF.34) and each authorising person's e-mail and optional mobile number; approval-path records; signed-link registry, with each link's recipient contact — e-mail and optional mobile number — and its passcode state (hash, attempts, invalidation); elevation requests; access reviews; break-glass seal state

## Provides

The authorise API to every service. Identity, grant, principal, consent and review commands to console-bff. Link issue and verification to console-bff, which serves the link pages. Links for masterdata-svc are issued on SignedLinkRequested, never by a call (DR-11). Token verification material to the gateway

## Capabilities

Authorisation decisions on role × permission × scope, evaluated live (FR-P.1, FR-Z.14). Separation of duty, including the sole-operator and agent approval paths (FR-P.2, FR-AB.13–AB.17). Identity-provider integration and group-to-grant mapping (FR-P.12, FR-AF.14). The grant lifecycle, expiry notices and ACC-003 (FR-AF.15). Quarterly access review (FR-AF.16). Orphaned-approver detection, ACC-001 (FR-AF.17). Principals of the six types and credential rotation (§22.5, FR-P.10). Signed-link passcodes: generation, delivery requests, attempt counting and invalidation with ACC-004 (FR-AG.70, VP-75). The per-user landing preference and the tenant's landing precedence (VP-73). The installation's agent-tenancy switch: switching off is refused while any agent consent is live, and switching on in a private installation needs the signed-link endpoint (VP-74, FR-AF.25). Agent consent, and the handover statement on withdrawal (FR-AB.8). Issuance and verification of signed links for external actors — client signatories, counterparties (§34.8, §34.9). Elevation and diagnostic-invitation approval (FR-AF.12). Break-glass access, ACC-002 (§34.6). Cross-tenant identities in the SaaS form (§34.7)

## Events published

- AccessAlertRaised
- AuthorityChanged
- MessageRequested

## Events consumed

- EntityProfileChanged
- OrganisationStructureChanged
- SignedLinkRequested

## Synchronous calls

None

## External dependencies

Identity provider (IdentityProvider, VP-01)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue` |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Cache authority data beyond FR-Z.14
- hold organisation structure
- own the business record behind a counterparty link — it issues the link; masterdata-svc owns the request
- deny a submission because it cannot evaluate: it denies the human action, never the submission (§18.6)

## Degradation

Each service keeps deciding from its authority replica while the replica's lag is within bound (FR-AH.5). Machine tokens verify at the gateway without a call, so ingestion and submission continue. New sign-ins, token issuance, grant changes and — once replicas fall behind the bound — human and agent actions are denied until access-svc returns. Break-glass (§34.6) covers identity-provider outages, not an access-svc outage
