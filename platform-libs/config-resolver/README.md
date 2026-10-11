# config-resolver

Precedence-ordered configuration resolution with effective dates and resolved-value recording

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every back-end service, including `control-plane-svc` (VP-45) |
| Part used | Resolution and resolved-value recording (INV-T-2869); setting, history, controls and bounds come with INV-T-2870 |
| Uses | tenancy-kernel (audit-log and outbox-and-queue with the setting path) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## What 1.0.0 offers (INV-T-2869)

- `PolicyReader` (CS-28, TPL-03): resolves a variation point at the transaction date (INV-REQ-1351) over the
  seven scope levels the catalogue row allows — client entity, AP connection, feed, entity, group, tenant,
  installation — then the operating profile's value (stored as a setting with source `PROFILE`, profile id
  and version, INV-Q-2979), then the catalogue default (by deployment form where the row says so).
  Unknown and retired keys are refused, a planned key resolves to its default, a must-elect key with
  nothing set raises `ElectionRequired` (INV-REQ-1251, INV-Q-2930).
- `VariationPointCatalogue`: the port a service adapts the generated gst-catalogue 1.2.0 `VariationPoint`
  to — scope levels, option keys with their per-option `Strictness` (never catalogue order, INV-Q-2936),
  multi-select, by-form default, form dependence.
- `ResolutionRecorder`: records the value and its source on one document version's record, insert-only,
  first write wins (INV-REQ-1068 b2, INV-AC-2093, INV-Q-2938).
- `ConfigPurge`: `purgeSubject` and `purgeTenant`, on a purge role, for the owning service's retention
  (VP-43) and tenant offboarding (INV-Q-2938).
- `ConfigSchema.ddl(schema, appRole, platformRole, purgeRole)`: the three tables, for the service's own
  Flyway migration (TPL-12).
