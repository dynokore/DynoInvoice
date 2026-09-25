# tenancy-kernel

Tenant context for each request and each message; row-level security with the session tenant set per request and cleared on return to the pool; the mandatory `tenant_id` column; isolation test support (§9.2, §13.3). The hierarchy itself is product data, read through the structure replica that `product-runtime` configures (§18.11). Entitlement is checked through the `LicenceCheck` binding (§12.1) and never blocks (FR-G.12)

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | The same services as `outbox-and-queue` |
| Part used | Tenant context, row-level security, the mandatory `tenant_id` |
| Uses | None |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).
