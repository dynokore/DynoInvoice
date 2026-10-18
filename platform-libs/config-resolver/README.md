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

## What 2.0.0 changes (INV-T-3983)

- One runtime login per service and a NOLOGIN `<svc>_sys` (INV-REQ-1189 b2, INV-ADR-3968; INV-Q-3931,
  INV-Q-3953): `ConfigSchema.ddl(schema, runtimeRole, sysRole, mode)`; the 1.x upgrade entry points are gone and
  there is no upgrade path from 1.x (INV-Q-4068).
- An installation value is stored only by `write_installation_setting`, with its audit entry and, in a service with
  an outbox (`EventMode.OUTBOX`), its `PolicyChanged`, staged in Kotlin by audit-log's `AuditStager` and
  outbox-and-queue's `OutboxWriter.stage` and checked in the database before anything is written; every row is
  stamped with database time (INV-Q-4254 A, INV-Q-4316). Bind the platform tenant with the setting actor
  (`bindPlatform(actor)`).
- `config_purge_subject` and `config_purge_tenant` delete in the tenant's own transaction with an actor bound.

## What 1.2.0 offers (INV-T-3176)

- **Cross-scope tighten-only** (VP-08; INV-REQ-780 b1, INV-Q-2936, INV-Q-3204): for a row with
  `crossScope`, a narrower value's directed numeric fields (`UP_STRICTER`/`DOWN_STRICTER`) may not be looser
  than the same-named fields of the value the nearest broader allowed scope resolves to; fields with no
  direction (VP-08's pair) are never compared. An option without a value counts by its catalogue default
  (`TypedValueSpec.default`), else its fixed number (`min` = `max`): `TypedValueSpec.absentAs`. VP-08's
  DEFAULT_PAIR_4H window is configurable from 1 to 8 hours; where nothing is set, the default resolves as
  `{"window":4}` (1.3.0, INV-Q-3320, INV-Q-3548). A write always carries the window (INV-Q-3167).
  Refused as `LOOSER_THAN_BROADER`.
- **Installation bound** (VP-57, VP-67; INV-REQ-1248, INV-Q-3202): a typed value's `min`/`max` may be
  `Bound.row(VP-79)`, the row named by `VariationPointDefinition.installationParameter`; config-resolver
  resolves that row at installation scope in the same service and needs one number in the same unit. Beyond
  it is refused as `BEYOND_INSTALLATION_BOUND`.
- Writes are checked on the effective date and on every later date a broader value or the bounding row already
  takes effect. A broader tightening is never refused: at resolution the narrower stored value's limited
  fields are capped at the broader value or bound in force on the date, the stored value unchanged, and
  `ResolvedSetting.caps` says what capped it (INV-REQ-1242 b5, INV-Q-3206). `PolicyReader.capToBounds`
  does the same for a per-object value a service stores itself (VP-67 per subscription); `validate` checks it.
- A bound that cannot be evaluated (no installation parameter row, a bounding row that resolves to an option
  with no number, a cross-scope row whose options do not carry the same directed fields) is refused
  (`NOT_SUPPORTED`, `BOUND_UNAVAILABLE`) or throws `BoundUnavailable` at resolution: fail closed.
- `ConfigSchema.upgradeTo120` (removed in 2.0.0): `config_resolved_value.capped_by`, so a recorded resolution keeps
  its caps.

## What 1.1.0 offers (INV-T-2870)

- `PolicyWriter.setPolicy` at every scope the row allows, installation included (INV-REQ-1242, INV-AC-2227):
  the setting role is checked against the actor's own roles for every source, profile values included
  (INV-Q-3169); a reason is required (INV-AC-2229); values are effective-dated and never retroactive by the
  Singapore business date, except a scope's first value back to the `BackdatingFloor` port's floor, which
  fails closed (INV-Q-2940, INV-Q-3140). Shape, numeric and enum bounds and record fields are checked here;
  named bounds, references, `rule` and cross-option constraints go to the `ValueConstraints` port, and an
  unbound port refuses (INV-AC-2224, INV-Q-3167). Rows declaring a cross-scope rule or an installation bound
  were refused as `NOT_SUPPORTED` (INV-Q-3173); 1.2.0 implements them. Each change writes an audit entry with the prior value and,
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
- `ConfigSchema.upgradeTo110` (removed in 2.0.0) for a 1.0.0 schema (expand-only); `ddl` now creates the 1.1.0
  tables.

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
- `ConfigPurge`: `purgeSubject` and `purgeTenant` for the owning service's retention (VP-43) and tenant
  offboarding (INV-Q-2938); from 2.0.0 through sys-owned functions on the runtime login.
- `OptionValueMaximum.maximum(key, option)`: the installation-wide maximum of an option's numeric value (the largest
  numeric member of the value stored over every tenant and scope; `null` when none), for evidence-svc's retention
  figure (INV-T-6538). It reads through the sys-owned function `config_option_value_maximum` (`ConfigSchema.OPTION_VALUE_MAXIMUM`),
  which refuses unless the bound tenant is the platform tenant (42501) and returns a number only. `JdbcOptionValueMaximum`
  implements it. A service embeds the new function by a new Flyway migration of its own, with the sys role's column
  grants and select-only policy on `config_setting` that `ConfigSchema.ddl` now carries; an applied migration is never edited.
- `ConfigSchema.ddl(schema, appRole, platformRole, purgeRole)` (2.0.0: `ddl(schema, runtimeRole, sysRole, mode)`):
  the three tables, for the service's own Flyway migration (TPL-12).
