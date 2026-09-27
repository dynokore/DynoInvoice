# adapter-runtime

Adapter lifecycle for external systems; the resilience primitives (timeout, jittered retry, circuit breaker, bulkhead) used on every edge — directly by external adapters (NFR-I.1), and on internal edges through `service-runtime` contract clients with the policy of §36.6 (FR-AH.8); dual-running during migration. Credentials are read through the `SecretStore` port (defined here, bound by `service-runtime` per STK-60); an adapter reports its status to the connection contract of §35.13

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every owner of an external dependency (§36.7): `ingestion-svc`, `masterdata-svc`, `rules-svc`, `transport-svc`, `evidence-svc`, `tenancy-svc`, `access-svc`, `notification-svc` |
| Part used | Adapter lifecycle and resilience primitives; the primitives reach every other edge through `service-runtime` contract clients |
| Uses | None |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
