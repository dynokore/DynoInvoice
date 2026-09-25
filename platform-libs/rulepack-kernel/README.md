# rulepack-kernel

Versioned, signed, effective-dated rule artefacts; activation fence, and the fence participant that verifies, compiles, holds and acknowledges a staged artefact; dry-run delta; decision trace; resolution by business date

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | `rules-svc`; the pack holders `payload-svc`, `tenancy-svc`, `reconciliation-svc` and `notification-svc`; `control-plane-svc` |
| Part used | `rules-svc`: feed and import client, registry, fence, resolution, evaluation, dry run. Pack holders: verify, compile, hold, acknowledge. `control-plane-svc`: bundle format and signing |
| Uses | tenancy-kernel |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
