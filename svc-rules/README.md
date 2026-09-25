# svc-rules

`rules-svc` — Rules as versioned data — which rules apply at a date, and what they say about a transaction, reproducibly

**Status:** design only; no code yet. This repository follows the design in [invoice-docs](http://registry.example.internal/invoice/invoice-docs). Change the design there first, then the code here.

| | |
| --- | --- |
| Design | [SDS §28.4](http://registry.example.internal/invoice/invoice-docs/-/blob/main/GST%20InvoiceNow%20Compliance%20Platform%20%E2%80%94%20SDS.md) |
| Wave | Wave 2: Foundation services (SDS §25.1) |
| Deployment forms | saas, private |
| Scaling | CPU-bound, stateless evaluation |

## Owns

Pack registry metadata; activation state and instance acknowledgements; drafts, approvals and dry-run results; import history of mapping spreadsheets (FR-AG.57)

## Provides

The evaluation API to canonical-svc, and to payload-svc for pre-issuance drafts. Authoring, approval and activation commands to console-bff

## Capabilities

Pack registry and activation state. Sole client of the pack feed and of offline bundle import; staging of each verified pack by RulePackStaged, and the fence on the holders' PackReadinessAcknowledged (§27.4, AD-31). Pack currency, DEP-001 to DEP-003, raised and cleared by PackCurrencyChanged (§12.4). Resolution by transaction date, with the credit-note rate exception (FR-AA.1). Pure evaluation of line scope verdict and category code, returning a decision trace (FR-B.6, FR-B.8). Scenario handler decisions: the scenario pack's handler returns the category code, the rate and the IBT-022 statement, or a RUL-004 or RUL-011 verdict (FR-AE.89); the effect on the payload is payload-svc's (§6.6; split by owner decision 25 Sep 2026). Activation fence (FR-K.7). Dry-run deltas (FR-B.3). Mapping authoring, with author/approver separation and source-class gating (FR-P.2, FR-AA.16–AA.18, RUL-007). Spreadsheet round-trip into drafts (§35.10). Private-form arming restricted to tax.approver (FR-AB.10). The wrong-pack compensation flow (§31.4.1). Pack types added on 24 Sep 2026: endpoint acceptance (§33.8), the amendment taxonomy in the MDE pack (§33.3), adjustment mapping (§33.11), intra-group rule (§33.12), return mapping (§33.10)

## Events published

- PackCurrencyChanged
- RulePackActivated
- RulePackStaged

## Events consumed

- PackReadinessAcknowledged

## Synchronous calls

None

## External dependencies

The pack feed and offline bundles, as the sole PackSource client (AD-31)

## Embedded modules (SDS §18.15)

| Layer | Modules |
| --- | --- |
| L1 | `service-runtime`, `outbox-and-queue`, `tenancy-kernel`, `config-resolver`, `audit-log`, `rulepack-kernel`, `adapter-runtime` |
| L2 | None |
| L3 | `product-runtime`, `gst-catalogue`, IRAS GST category code catalogue, scope rules, scheme scenario handlers |

Shared L1, L2 and `product-runtime` modules come from `platform-libs` as published versions; `gst-catalogue` comes from `platform-contracts`. Service-internal L3 modules live in this repository.

## Must not

- Resolve against now() (FR-B.1, FR-F.1)
- act as a pack repository other services read from (FR-Z.9)
- know the submission type or the transport
- permit in-place edits to an activated pack (FR-B.4)
- allow self-approval (FR-P.2)
- execute logic embedded in a pack (§3.5)
- persist decision traces (AD-24)

## Degradation

Unavailable: new documents hold at INGESTED. A partial pack load aborts activation entirely (FR-K.7)
