# rulepack-kernel

Versioned, signed, effective-dated rule artefacts; activation fence, and the fence participant that verifies, compiles, holds and acknowledges a staged artefact; dry-run delta; decision trace; resolution by business date

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | `rules-svc`; the pack holders `payload-svc`, `tenancy-svc`, `reconciliation-svc` and `notification-svc`; `control-plane-svc` |
| Part used | `rules-svc`: feed and import client, registry, fence, resolution, evaluation, dry run. Pack holders: verify, compile, hold, acknowledge. `control-plane-svc`: bundle format and signing |
| Uses | tenancy-kernel, crypto (CS-53) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Pack registry DDL: precondition

`PackRegistryMigration.ddl` expects `<schema>.bound_tenant()` and `<schema>.bound_actor()` to exist in the schema before it runs. It does not create them and issues no role switch (INV-Q-6663): tenancy-kernel's `BindingKeyMigration.ddl` runs once per schema as the `<svc>_sys` login and writes the key table and the two functions. Who runs it in an installation is INV-Q-6580 (owner, unanswered). On a schema without the functions the DDL raises SQLSTATE 42883 at the first policy that names `bound_tenant()` and creates nothing.

The registry table names its pack version column `pack_version`, not `version`, so that `gst.jooq-codegen` accepts the table (INV-Q-7438).
