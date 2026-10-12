# audit-log

The audit-entry writer: actor, action, scope, reason and prior state, written in the same transaction as the change as `AuditEntryRecorded` (FR-AH.15); the override path (FR-O.2, FR-O.3, §11.7). Storage, immutability and export are `evidence-store`, in the service that embeds it (§18.15; §9.9, AD-24)

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every back-end service; `control-plane-svc` writes into its own schema (§28.12) |
| Part used | Audit-entry writer and the override path; storage is `evidence-store` in `evidence-svc` |
| Uses | outbox-and-queue (where the service has a data-plane schema) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## Use

- `audit-log-api`: `AuditEntry`, `AuditWriter`, `AuditStager` and `StagedAuditEntry` (build-only, INV-Q-4277), `AuditedOperations`, `OverrideService`, `OverrideRegister`, `ErrorCatalogue`, `OverridePolicy`.
- `audit-log` (impl): `OutboxAuditWriter` (over outbox-and-queue's `OutboxWriter`), `LocalAuditWriter` (also the `AuditStager`), `AuditSchema.auditEntryDdl(schema, appRole)` and the audited purge function `AuditSchema.purgeDdl(schema, runtimeRole, sysRole, service)` for a service without an outbox (`control-plane-svc`, INV-Q-2958, INV-API-3822), `TransactionalAuditedOperations`, `JdbcOverrideService`, `AuditSchema.ddl(schema, appRole)` for the service's migration.
- The owning service supplies the `ErrorCatalogue` (TERMINAL, MANUAL or OVERRIDABLE per code) and the verified roles of the caller. Authorised role: `tax.approver`; one `tax.approver` per document version (VP-32 default, INV-REQ-1293); no extra roles.
