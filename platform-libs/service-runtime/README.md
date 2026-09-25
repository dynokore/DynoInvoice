# service-runtime

Common service bootstrap and configuration; health and degraded readiness (FR-AH.17); the uniform diagnostic endpoint (FR-AE.82); telemetry and trace context — `correlation_id`, `causation_id` — propagated across calls and logs and supplied to the event envelope (§11.6); contract clients with deadline propagation and the per-edge policy of §36.6 (FR-AH.7, FR-AH.8); workload identity (FR-AH.6); the replica mechanism for structure, authority and policy (AD-26, FR-AI.3); the platform-event schemas (AD-27, FR-AH.15); the job contract (FR-AG.62) and the connection contract (§35.13); and the closed set of binding interfaces of §12.1

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every back-end service — every service but `console-web` |
| Part used | All; `control-plane-svc` uses the bootstrap, bindings and telemetry, not the replicas |
| Uses | the other L1 modules its service embeds (composition root) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
