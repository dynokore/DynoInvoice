# config-resolver

Precedence-ordered configuration resolution with effective dates and resolved-value recording

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every back-end service, including `control-plane-svc` (VP-45) |
| Part used | Resolution and resolved-value recording (INV-T-2869); setting, history, controls and the law floor (INV-T-2870) |
| Uses | tenancy-kernel, audit-log, outbox-and-queue (optional: without an outbox only the audit entry is written) |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## What 1.1.0 offers (INV-T-2870)

- `PolicyWriter.setPolicy` at every scope the row allows, installation included (INV-REQ-1242, INV-AC-2227):
  the setting role is checked against the actor's own roles for every source, profile values included
  (INV-Q-3169); a reason is required (INV-AC-2229); values are effective-dated and never retroactive by the
  Singapore business date, except a scope's first value back to the `BackdatingFloor` port's floor, which
  fails closed (INV-Q-2940, INV-Q-3140). Shape, numeric and enum bounds and record fields are checked here;
  named bounds, references, `rule` and cross-option constraints go to the `ValueConstraints` port, and an
  unbound port refuses (INV-AC-2224, INV-Q-3167). Rows declaring a cross-scope rule or an installation bound
  are refused as `NOT_SUPPORTED` (INV-Q-3173). Each change writes an audit entry with the prior value and,
  where the service has an outbox, `PolicyChanged` in the same transaction; tenant and group changes use
  `EntityRef.TENANT_WIDE` (INV-Q-3165).
- `acknowledgeControlRelaxation` (INV-AC-205, INV-AC-2230): a looser option of a waivable CONTROL needs every
  acknowledger role its catalogue row names (`OptionSpec.acknowledgers`: role or role by form, `ALWAYS` or
  `UNDER_DELEGATION`, distinct from the setter), each by a distinct person; nobody accountable means refused
  (INV-Q-3134). A client-entity scope is always delegated, otherwise the `DelegationProbe` port decides and
  an unbound or failing probe counts as delegated (INV-Q-3136). Acknowledgements are kept in
  `config_relaxation_acknowledgement`, grouped by `relaxation_id`, and back exactly one matching setting
  (INV-Q-3138). A looser option at installation is refused in 1.1.0.
- `PolicyHistory`: every version of a scope's value, the as-of view (in force on the date, and what changed
  since: recorded or effective after it; keys from `VariationPointCatalogue.keys()`) and compare
  (INV-AC-1146).
- `ConfigSchema.upgradeTo110` for a 1.0.0 schema (expand-only); `ddl` now creates the 1.1.0 tables.

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
