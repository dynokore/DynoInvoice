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

## Transaction binding

`TransactionRunnerImpl` binds the tenant and the actor as the first statement of every transaction, both
transaction-local (`set_config(..., true)`). An optional `ConnectionLabel` (third constructor argument, since 1.4.0;
INV-T-3982, coordinator ruling INV-Q-4487) adds `set_config('application_name', ?, true)` to that same statement with
the name it gives for the transaction's connection, so pg_stat_activity shows it while the transaction runs and it ends
at commit or rollback. service-runtime passes one that names the pool lane (`<gst.service>:<lane>`). Without a label,
or when it gives `null`, the statement is unchanged.
