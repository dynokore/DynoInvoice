# Changelog

All notable changes to `outbox-and-queue` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

## [6.1.4] - 2026-10-19
### Changed
- Test fixtures and tests run under the signed tenant binding (INV-T-5363, slice 6 of INV-T-4156, INV-Q-5352, INV-Q-6663): every runner built in the module's sources signs `app.binding_sig`, and the plain-connection binds sign. Comments name the tenant binding of tenancy-kernel instead of `app.tenant_id`. No behaviour or API change.
- Precondition from INV-T-4156 on: a schema that holds this module's tables must also hold `bound_tenant()` and `bound_actor()`, created once per schema by tenancy-kernel's `BindingKeyMigration.ddl` as the sys login before this module's DDL (INV-Q-6586; who runs it is INV-Q-6580). The module's DDL neither creates them nor switches role; the tests create them in `SigningFixture`.

## [6.1.3] - 2026-10-18
### Changed
- Metadata-only release (INV-T-4224, G-DVS): depends on `tenancy-jooq` 1.3.0, whose published module metadata changed; no code change.

## [6.1.2] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5365, G-DVS): depends on `tenancy-jooq` 1.2.3, whose published module metadata changed; no code change.

## [6.1.1] - 2026-10-18
### Changed
- Metadata-only release (INV-T-5359, G-DVS): depends on `tenancy-kernel` 3.3.0, whose published module metadata changed; no code change.

## [6.1.0] - 2026-10-18
### Added
- INV-T-6291 (coordinator ruling INV-Q-6281, option A, owner delegation 2026-10-18; INV-REQ-1210, INV-REQ-1205): the event
  log keeps a topic marked `retained_while_resolvable: true` in `event-log/topics.yaml` for a third period,
  `Retention.packArtefactRetention` (last constructor argument, default and floor 1827 days; the two-argument form
  remains). `CatalogueEntry.retainedWhileResolvable` (`Boolean?`) and `ReplayTopic.retainedWhileResolvable` are read by
  `TopicsYamlCatalogue` like `content_topic` (a replay topic without the line is refused). `TopicPurge` reports
  `retainedWhileResolvable`; `EventLogSchema.Topic` gains it; the retention table gains `pack_retention`
  (`EventLogSchema.retentionUpgradeDdl()` adds it to an existing installation and must run at every install and upgrade
  before `writeRetention`, which writes the third value); `purge_batch()` and `events_missing()` choose the pack period
  for a marked topic and treat a row with `pack_retention` null or below 1827 days like a missing row.
  `inboxRetention` and `UnrelayedEventSettings.forRetention` are unchanged.
- Deployment order: `JdbcEventLogPurge` refuses, naming the topic, a topic without one `retained_while_resolvable`
  marker and a topic marked both `content_topic` and `retained_while_resolvable`, so a service composition root that
  loads a `topics.yaml` without the line fails at start. INV-T-6287 (platform-contracts) must be published in the
  contracts bundle before a service takes this version.

## [6.0.1] - 2026-10-18
### Changed
- INV-T-4202: the test fixtures build their pools through tenancy-kernel's `TestPoolFixture` (new `runtimePool`, `purgePool`, `rolePool`; `eventLogScope` takes a pooled, tenant-aware source); no production code change.

## [6.0.0] - 2026-10-17
### Changed
- BREAKING (INV-T-5306, slice 3 of 4 of INV-T-4202; owner ruling INV-Q-4196): the event-log reader, purge, staleness
  monitor and replay classes run every statement as jOOQ on a tenancy-jooq `DSLContext` and take the `TenantScope` (the
  event-log scope whose `TenantAwareDataSource` holds the lane gate) and the `DSLContext` instead of a `DataSource`:
  `PostgresEventLogReader(eventLog, dsl)`, `JdbcEventLogPurge(eventLog, dsl, catalogue)`,
  `JdbcRetentionPurge(scope, dsl, schema, retention, clock, batch)`,
  `JdbcStalenessMonitor(scope, dsl, lag, catalogue, schema, rules, clock, sink)`; `JdbcReplayIntake` and
  `JdbcReplayResponder` lose the claim `DataSource` (the claim is a jOOQ routine call in the scope). The SQLSTATE of a
  failed statement is read through the scope's `sqlErrors` (`sqlStateOf`, INV-Q-5121), so `EventBindingUnavailable`
  keeps its messages. `JdbcConsumerReplay(schema, dsl, head, outbox, clock)` takes the `DSLContext` in place of the
  `CurrentConnection` and runs in the caller's open transaction.
- `JooqRoutines` gains the event-log `claim` and `purge_batch` table functions and the `purge_expired`,
  `full_sync_delete` and `read_model_clear` routine calls; `EventLogSchema.jooqTables` declares `claim`, `purge_batch`,
  `purge_watermark` and `retention` (`PLATFORM_SCOPED`) and `EventLogSchema.writeRetention(dsl, retention)` is one jOOQ
  statement; `OutboxSchema.jooqTables` declares `consumer_replay`, `consumer_replay_marker` and `consumer_source_seen`.
- The read-model clear function that `OutboxSchema.readModelClearFunction(..., filterColumn)` generates takes `p_value text`
  and converts it with `v := p_value` into a `<column>%type` variable before the blank check, so the filter reaches the
  server as a text bind (no custom jOOQ binding); rows deleted and SQLSTATEs are as before. A consumer re-runs the
  generated DDL; a column-typed overload already in a database stays and is not chosen.
- Marker writes of `JdbcReplayIntake` go through tenancy-jooq, in a platform-bound transaction of their own, so a
  `ReplayCompleted` event under any tenant records its marker as before.

## [5.0.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5358, G-DVS): depends on `tenancy-kernel` 3.2.0, whose published module metadata gained a jOOQ dependency; no code change.

## [5.0.0] - 2026-10-17
### Changed
- BREAKING (INV-T-5305, slice 2 of 4 of INV-T-4202; owner ruling INV-Q-4196): `JdbcWorkQueue` and `LaneRateLimiter` run
  every statement as jOOQ on a tenancy-jooq `DSLContext` and take the `TenantScope` and the `DSLContext` instead of a
  `DataSource` (`JdbcWorkQueue(scope, dsl, schema, limiter, clock, consumer, claimTimeout)`,
  `LaneRateLimiter(scope, dsl, schema, limits)`). `claim_due` is a jOOQ table-valued function call and the rate-limit
  upsert one jOOQ statement on the database clock, each in a transaction of its own under the platform binding.
  `QueueSchema.jooqTables(schema)` declares `work_queue` (tenant), `lane_rate_window` and `claim_due`
  (`PLATFORM_SCOPED`); `OutboxSchema.jooqTables(schema)` adds them, `event_dead_letter`, `consumer_sequence` and
  `consumer_offset`.
- BREAKING: `JdbcInboxConsumer` takes the `DSLContext` after its `TenantScope` and no claim `DataSource`; its offset
  reads and writes outside the tenant transaction, the dead-letter record, the inbox claim and the sequence statements
  are jOOQ. The string-built keep-unless-moved helper is gone: the dead-letter offset upsert is one jOOQ statement with bind values that resets
  attempts, infra_failures, first_infra_failure_at and retry_not_before when the new offset is greater than the stored
  one. `JdbcReplayIntake` takes the `DSLContext` after its `TenantScope` (it builds the inbox consumer).
- BREAKING: `JdbcDeadLetterTriage` takes the `DSLContext` after its `TenantScope` and no `DataSource` (the first
  parameter is gone); the raw read, the replay enqueue and the resolve call are jOOQ.
- Added to `OutboxSchema.ddl` (coordinator rulings INV-Q-5873, INV-Q-5875): the sys-owned SECURITY DEFINER routines
  `consumer_offset_lock`, `consumer_offset_advance` and `consumer_source_seen_record` (the offset lock, advance and
  source-seen insert that `JdbcInboxConsumer` calls inside the record's tenant transaction, so the offset still moves
  atomically with the inbox row) and the table function `event_dead_letter_triage_page` (the triage read, declared
  `PLATFORM_SCOPED` in `OutboxSchema.jooqTables`). Each has the fixed `search_path`, EXECUTE revoked from PUBLIC and
  granted to the runtime login only. The sys role gains `select, insert, update` on `consumer_offset` and `insert` on
  `consumer_source_seen`; the runtime login keeps its direct grants. A service re-runs the DDL of a new release to get the
  routines and grants.

## [4.0.2] - 2026-10-17
### Changed
- KDoc only: a tenant id in `consumer_replay.scope` is request metadata, not tenant data (INV-T-5821, owner answer INV-Q-4817 A); no behaviour change.

## [4.0.1] - 2026-10-17
### Changed
- Metadata-only release (INV-T-5820, ruling INV-Q-5896 A): depends on `tenancy-jooq` 1.2.0 (the guard refuses a `TenancyTables` not built by `TableClasses.verify`, INV-Q-4821 B); no code change.

## [4.0.0] - 2026-10-17
### Changed
- BREAKING (INV-T-5304, slice 1 of 4 of INV-T-4202; owner ruling INV-Q-4196): `JdbcOutboxWriter`, `JdbcOutboxRelay`,
  `PostgresEventBinding`, `JdbcEventLogPositions`, `JdbcUnrelayedEventCheck` and `PostgresEventLogPresence` run every
  statement as jOOQ on a tenancy-jooq `DSLContext` (`OutboxSchema.jooqTables`, `EventLogSchema.jooqTables`) and take
  it as a constructor argument. `JdbcOutboxWriter` takes the `DSLContext` instead of `CurrentConnection`;
  `JdbcOutboxRelay` and `JdbcUnrelayedEventCheck` take the `TenantScope` and the `DSLContext` instead of a claim
  `DataSource`; `JdbcEventLogPositions` and `PostgresEventLogPresence` take the event-log `TenantScope` and the
  `DSLContext` instead of the event-log `DataSource` (coordinator ruling INV-Q-5614); `PostgresEventBinding` takes the
  `DSLContext` after its scope and reads the SQLSTATE through the scope's runner (INV-Q-5121); the
  `EventBindingUnavailable` messages are unchanged.
- `JdbcOutboxRelay` takes its run-spanning advisory lock in an outer platform transaction and runs the claim read and
  each row in `TransactionRunner.outsideOpenTransaction` (owner answer INV-Q-5609 (b)); it takes its lane name and
  size and refuses to start with fewer than 2 permits (`IllegalStateException`).
### Added
- `OutboxSchema.OUTBOX_CLAIM_PAGE` and `OUTBOX_CLAIM_PENDING`: sys-owned SECURITY DEFINER functions, created by
  `OutboxSchema.ddl`, through which the relay and the unrelayed-event check read the claim set (coordinator ruling
  INV-Q-5612); the claim view stays in the DDL.
- `TenantScope.sqlErrors`; test fixtures `OutageTransactionRunner` and `EventLogOutage`, and the seam
  `KillableTransactionRunner.outsideOpenTransaction` (additive).

## [3.1.1] - 2026-10-16
### Changed
- Metadata only: depends on `tenancy-kernel` 3.1.0 (adds `TransactionRunner.outsideOpenTransaction`, INV-T-5675); version step per ruling INV-Q-5678 (A). No code change.

## [3.1.0] - 2026-10-15
### Added
- `OutboxPostgresFixture` (test fixtures, additive; coordinator ruling INV-Q-5119): the `initSql` constructor argument
  (provisioning SQL run once at container start as the owner role), `jdbcUrl()`, `adminUser()`, `adminPassword()`, the
  login strings `OWNER`, `OWNER_PASSWORD`, `APP_PASSWORD`, `WORKER_PASSWORD`, `MIGRATE_PASSWORD`, and the init-SQL
  builders `serviceInitSql`, `eventLogInitSql`, `haEventLogInitSql` (the event log in the second database `ha_log`, with
  `haEventLogUrl()`) and `captureActorsInitSql`, and the JDBC-free factories `eventLogReader()` and `stalenessMonitor(...)`.
  Its `DataSource`-returning members stay.

## [3.0.1] - 2026-10-15
### Fixed
- `JdbcReplayDelivery.runOnce()` holds at most one database connection of its DataSource at any instant (INV-T-4854;
  coordinator ruling INV-Q-4850): the payload is read in one short tenant transaction, an invalid or foreign-tenant
  envelope is dead-lettered on the claim connection outside any transaction and the item then completed in a second
  short transaction, a valid one is applied and completed in one transaction as before. An item is completed only after
  its dead letter is recorded. Outcomes, `event_dead_letter` rows and queue state are unchanged;
  `JdbcInboxConsumer.applyReplayed` keeps its signature and result. A lane of two permits can no longer deadlock a
  replay delivery against a relay run that holds its claim connection.

## [3.0.0] - 2026-10-15
### Changed
- Tenantless tables are classified (INV-T-4134; INV-ADR-3968 item 4a, INV-REQ-854 b6; owner ruling INV-Q-4113):
  `consumer_offset`, `consumer_replay`, `consumer_replay_marker`, `consumer_source_seen` and `lane_rate_window` hold no
  tenant data and carry the table comment `platform-scoped: no tenant data` in `OutboxSchema.ddl` and
  `QueueSchema.ddl`; `OutboxSchema.platformScopedTables(schema)` and `EventLogSchema.platformScopedTables()` list them
  (with the event log's own tenantless tables, among them the purge watermark) for the service's declaration.

### Changed (breaking)
- `event_dead_letter.tenant_id` is `uuid NOT NULL` (CS-19): `event_dead_letter_record` stores a record that names no
  tenant under the reserved platform tenant, and `DeadLetterTriage` reads such a letter with `tenant = null`, as
  before. A restrictive SELECT policy `event_dead_letter_no_platform_tenant` `TO` the runtime role
  (`tenant_id <> platform tenant`) keeps such a letter out of every session of the runtime login, whatever tenant it is
  bound to, so its `raw` is never read (coordinator ruling INV-Q-4819; INV-Q-3130); the sys-owned triage view still
  lists it. A database migrated from an earlier DDL must set the NULL rows to the platform tenant before the column is
  made NOT NULL.

### Migration
1. In an existing database, update the NULL `tenant_id` rows of `event_dead_letter` to the reserved platform tenant
   before the column is made NOT NULL (`update <schema>.event_dead_letter set tenant_id = <platform tenant> where
   tenant_id is null`), then apply the new DDL (`alter column tenant_id set not null`, the restrictive policy
   `event_dead_letter_no_platform_tenant`).

## [2.2.3] - 2026-10-14
### Fixed
- `TenantScope.workloadPrincipal` uses tenancy-kernel's `BlankText.isActor` instead of `isNotBlank()` (coordinator
  ruling INV-Q-4684 (A), INV-T-4686; INV-Q-4534, INV-Q-4614, INV-Q-4622; INV-REQ-854 b1): a workload principal that
  is blank by the shared set or holds an ISO control character is refused with an `IllegalArgumentException` that
  never echoes the value.

## [2.2.2] - 2026-10-14
### Fixed
- The full-sync delete (`p_consumer`, `p_source`), `event_dead_letter_record` (`p_consumer`) and the generated
  per-column clear function (`p_value`) check blank values with tenancy-kernel's `BlankText` instead of `btrim`
  (spaces only) (coordinator rulings INV-Q-4614 (A) and INV-Q-4622 (A), INV-T-4616). Refusals keep their error codes and messages.

## [2.2.1] - 2026-10-14
### Changed
- Metadata-only release (INV-T-4310, coordinator ruling INV-Q-4552): no behaviour or API change.
- The POMs of both publications (api and impl) declare the company licence, `Dynokore Proprietary Licence`
  (SPDX ref `LicenseRef-Dynokore-Proprietary`, no URL yet), stamped by platform-build 0.11.0 (INV-T-4310; owner
  rulings INV-Q-4286 (C) and INV-Q-4284 (A), STK-66, INV-REQ-1383).
- Source formatted to ktlint_official (platform-build 0.11.0, INV-Q-4426, INV-T-4556); formatting only.
### Fixed
- tests no longer depend on the calendar date (INV-Q-4638)

## [2.2.0] - 2026-10-13
### Added (INV-T-4337, owner rulings INV-Q-4334 B and INV-Q-4350 A)
- `eventlog.events_missing(topic, event_ids)`, a fourth `eventlog_sys` SECURITY DEFINER function (`EventLogSchema`;
  INV-REQ-1205 b3, INV-AC-2175): of at most 1,000 ids (more is refused, SQLSTATE 22023), returns only those not in
  the topic, and the topic's retention cut-off (`now()` less the content window or the event-log retention, the
  purge's own cut-off); never content. EXECUTE is revoked from PUBLIC and granted to the producer logins only, and a
  caller that does not produce the topic is refused (42501) per the new `eventlog.producer` table, generated from the
  topics like `eventlog.subscription` (owned by the migration login, SELECT to `eventlog_sys` only). `eventlog_sys`
  gains the column SELECT of `event_id`; each topic table gets an index on `event_id` in `topicDdl`. Coordinator
  ruling INV-Q-4264's "no fourth eventlog_sys function" is superseded for this purpose only.
- `UnrelayedEventCheck` (api) and `JdbcUnrelayedEventCheck` (impl): finds every outbox row whose `published_at` is
  set but whose event id is not in its topic, the residual risk of INV-ADR-3968's consequences (owner ruling
  INV-Q-4334). Every run reads all published rows through the sys-owned claim view (no position is skipped, so a row
  written or marked by a transaction open across a run is read by the next) and their topic and id per tenant; ids
  not yet verified are sent per topic in batches through `EventLogPresence` (`PostgresEventLogPresence`). A row is
  reported only when published more than `UnrelayedEventSettings.margin` after the returned cut-off (coordinator
  ruling INV-Q-4403 (A): default 1 hour, refused when not positive or not below the shortest topic retention), so an
  event purged by the event log is never reported. Each finding (`UnrelayedEvent`: service, topic, event id, outbox
  seq, `published_at`) is raised to the `UnrelayedEventSink` once per row across instances and restarts: the run that
  first records it in the service's `inbox` under the consumer `outbox-unrelayed-event-check` (existing INSERT grant,
  `on conflict do nothing`) raises it.

## [2.1.1] - 2026-10-13
### Fixed
- The published impl POM (`outbox-and-queue`) declared no dependency (INV-T-4356; defect in INV-T-2860's and
  INV-T-3937's per-module publishing, STK-60, STK-01 rule (a), TPL-01): the `impl` publication was built with
  `artifact(implJar)` and no software component, so a consumer declaring platform-libs-bom and this impl artefact had
  to list `outbox-and-queue-api` and the other modules itself. The impl POM now declares `outbox-and-queue-api` at the
  same version and the first-level dependencies of the impl runtime classpath, generated and checked by the root
  build.gradle.kts (`verifyImplPoms`): outbox-and-queue-api:2.1.1 (compile), tenancy-kernel:1.3.1 (compile),
  tenancy-kernel-api:1.3.1 (compile), org.jetbrains.kotlin:kotlin-stdlib:2.4.20 (compile), org.slf4j:slf4j-api:2.0.16
  (runtime), tools.jackson.core:jackson-databind:3.2.3 (compile). The classes are unchanged.

## [2.1.0] - 2026-10-12
### Added (INV-T-3983)
- `OutboxWriter.stage(events, context)` and `StagedOutboxRow` (api): the build-only half of `append` (coordinator
  ruling INV-Q-4254 A). It returns each event's envelope (UUIDv7 id, source, time, instance, correlation, causation)
  and catalogued topic under the same EmissionGuard checks (INV-AC-1869) and writes nothing. Unsequenced platform
  events only: a domain type, a Doc UUID or an aggregate version is refused, so no `docsequence` is reserved. The
  interface's default refuses (`UnsupportedOperationException`), so existing implementations compile unchanged;
  `JdbcOutboxWriter` implements it, and a staged row equals the row `append` writes (test).
- `OutboxSchema.ddl` grants `<svc>_sys` `INSERT` on `outbox`, for a sys-owned definer that writes a platform event
  together with the change it records (config-resolver's `write_installation_setting`, INV-ADR-3968 item 6, owner
  ruling INV-Q-3931). No sys policy is added: the tenant policy alone admits the row, so the sys role inserts only
  under the bound tenant (the platform tenant for a platform event; test).

### Changed (INV-T-3983, owner ruling INV-Q-4334 B: outbox UPDATE hardening)
- The runtime login keeps `SELECT` and `INSERT` on `outbox` and holds `UPDATE` on `published_at` only: the
  table-wide `UPDATE` is removed, and there is still no `DELETE` (purging stays with `outbox_purge_expired`).
- A `BEFORE UPDATE` trigger on `outbox` (`outbox_publish_once`) lets `published_at` change only from NULL. It stores
  `clock_timestamp()` and ignores the value passed. Re-setting, clearing or backdating a published row is refused
  (SQLSTATE 42501), and so is a change to any other column. A written row's content and actor stay as written, and
  no unpublished row can be brought into the purge's range. The relay's statement is unchanged.
- Residual risk (INV-ADR-3968 consequences): marking an unrelayed row published at the current time stays
  possible. Detecting it is the ruling's follow-up task.

## [2.0.0] - 2026-10-12
### Changed (breaking; the event log's cross-tenant rights move to eventlog_sys functions, INV-T-3979)
- The event log on one runtime login per service (owner ruling INV-Q-3953; coordinator rulings INV-Q-4262,
  INV-Q-4264, INV-Q-4266, INV-Q-4268; INV-REQ-854, INV-REQ-1205 b3, b4, b6). `EventLogSchema` works on (runtime, sys):
  `schemaDdl(sys)` creates `eventlog.subscription`, `purge_watermark`, `record_position` and the one-row `retention`
  table (platform-scoped, owned by the event log's migration role, SELECT for `eventlog_sys` only, CHECK of at least
  7 days on each value); `topicDdl(Topic, sys)` grants INSERT to the topic's producer logins and SELECT under the tenant
  policy to its subscriber logins, and the cross-tenant claim and purge policies to `eventlog_sys` only;
  `functionsDdl(topics, sys, purgeLogin)` regenerates the subscriptions, the record-topic keepers, each subscriber's
  SELECT on its topics' watermark rows and the three SECURITY DEFINER functions owned by the NOLOGIN `eventlog_sys`,
  with static per-topic branches over `SqlIdentifier`-checked names, `SET search_path = pg_catalog, eventlog,
  pg_temp`, and on every re-creation the owner, the search path, EXECUTE revoked from PUBLIC and the grants again:
  `claim(topic, after, limit)` (offsets, tenants and insertion times of a topic the calling login subscribes to; nothing
  for any other login), `purge_batch()` (EXECUTE for the purge login only) and `record_position(topic, offset)` (the
  topic's keeper only, compared with `session_user`). A topic's key is its table name (`EventLogSchema.tableName`,
  column `topic_key`), checked injective when the functions are generated.
- Retention is the event log's own row (coordinator ruling INV-Q-4262): `EventLogSchema.writeRetention` writes it with
  bound parameters (default 30 days) at every install and upgrade; with the row missing or below 7 days `purge_batch()`
  deletes nothing and `JdbcEventLogPurge` logs an error. `JdbcEventLogPurge(purgeSource, catalogue)` calls
  `purge_batch()` until no topic has a full batch left (`EventLogSchema.PURGE_BATCH_SIZE`); a record topic is never
  purged above its keeper's recorded position.
- Head and backlog through `claim` only (coordinator ruling INV-Q-4264): `EventLogHead.head(topic, from)` gallops from
  the consumer's committed position; `EventLogLag.backlog` is the head less the position, an upper bound of the records
  unconsumed (offsets the purge removed or that were never committed are counted), and its lag is the age of the oldest
  unconsumed row.
- `PostgresEventBinding(eventLog: TenantScope)` and `PostgresEventLogReader(eventLogSource, eventLog: TenantScope)`: one
  event-log DataSource on the service's runtime login (INV-Q-4268), transactions opened by tenancy-kernel bound to the
  tenant and the workload principal; no event-log code sets a session setting itself.
- `TenantScope(tenants, transactions, connections, workloadPrincipal)`: `bind(tenant, actor, block)` binds an actor
  always, `bindWorkload(tenant, block)` the service's workload principal for background work (relay, work queue, inbox
  consumers, dead-letter triage, replay); the actor-less `bind(tenant, block)` is removed (INV-REQ-854 b1).
- Every generated SQL identifier is checked by tenancy-kernel's `SqlIdentifier`; this module's own copy is removed.
- One runtime login per service (INV-T-3978; owner rulings INV-Q-3951, INV-Q-3953, INV-Q-3965;
  INV-ADR-3968; INV-REQ-854, INV-REQ-1189 b2, INV-REQ-1205). `OutboxSchema.ddl(schema, runtime, sys)` and
  `QueueSchema.ddl(schema, runtime, sys)` replace the (app, worker) forms. The runtime login works on the tenant tables
  under the forced tenant policy only; every cross-tenant policy targets the NOLOGIN `<svc>_sys` and is reached only
  through objects it owns: the column-limited views `outbox_claim_v` and `event_dead_letter_triage_v`, and SECURITY
  DEFINER functions with static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE revoked from PUBLIC and
  granted to the runtime login only: `claim_due` (work queue), `full_sync_delete(consumer, source)`,
  `outbox_purge_expired`, `inbox_purge_expired` and `event_dead_letter_purge_expired` (each with its age predicate and
  the retention floor in SQL), `event_dead_letter_record` and `event_dead_letter_resolve`.
- `JdbcOutboxRelay`, `JdbcWorkQueue`, `JdbcInboxConsumer`, `JdbcDeadLetterTriage`, `JdbcRetentionPurge` and
  `JdbcReplayResponder` take a runtime-login DataSource and use those views and functions; the relay's and the replay
  responder's session advisory locks are transaction-scoped (`pg_try_advisory_xact_lock`), so no pooled connection
  keeps a lock outside its transaction (transaction-mode pooler, INV-ADR-3968 item 9).
- Full sync on the runtime login (coordinator ruling INV-Q-4064): `OutboxSchema.readModelClearFunction(schema, table,
  runtime, sys, filterColumn?)` creates, from a static template, the sys-owned SECURITY DEFINER function
  `<schema>.<table>_full_sync_clear` of one read-model table (rows whose filter column equals its non-blank argument,
  or every row; a protected evidence ledger, coordinator ruling INV-Q-4034, is refused). `ReadModelClear` names the
  tables and their filter values (`ReadModelTable`) instead of SQL statements. `JdbcConsumerReplay.fullSync` runs on
  the runtime login bound to the platform tenant and calls those functions and `full_sync_delete` with bound
  parameters.

### Added
- `EventLogPositions` and `JdbcEventLogPositions`: the keeper's thin call of `eventlog.record_position` (INV-Q-4266);
  nothing calls it automatically.

### Removed (breaking)
- `OutboxSchema.fullSyncWorkerAccess` and `OutboxSchema.readModelClearAccess`: no `OutboxSchema` or `QueueSchema` entry
  point takes a worker role.
- `EventLogSchema.ConsumerRoles`, `EventLogSchema.watermarkDdl` and the relay, claim, reader and purge roles of the
  event log; the event-log purge's `Retention`, clock and batch-size parameters.
- The 1.x upgrades `OutboxSchema.upgradeTo110`, `upgradeTo120`, `upgradeTo130`, `upgradeTo140`, `upgradeTo150`,
  `upgradeTo1100` and `QueueSchema.upgradeTo170` (coordinator ruling INV-Q-4068): there is no upgrade path from a 1.x
  schema; a service creates its schema with the new `ddl`.

## [1.10.0] - 2026-10-11
### Added
- Rebuild of a consumer behind the purge watermark by the producer's replay (INV-T-3385; INV-REQ-1205 b3, b4, b6;
  INV-REQ-1372; rulings INV-Q-3599, INV-Q-3601, INV-Q-3625, INV-Q-3644, INV-Q-3669, INV-Q-3671, INV-Q-3676;
  INV-AC-3657, INV-AC-3658). The catalogue holds the replay topics (`gst.<producer>.replay-<subscriber>.v1`, one per
  (producer, consumer) pair; `gst.platform.replay-<subscriber>.v1` for 'every service') and the request topics
  (`gst.<consumer>.replay-requested.v1`); `TopicsYamlCatalogue` parses both new sections of topics.yaml and the
  pinned excerpt is re-copied from platform-contracts main. ReplayRequested and ReplayCompleted are platform events.
- `JdbcConsumerReplay`: in the caller's platform transaction, moves the consumer's positions past
  max(head, watermark) and writes ReplayRequested through the outbox (`consumer_replay` keeps the pending replay;
  a replay stays pending until its incident closes, one per (consumer, producer) pair, INV-Q-3845).
- `JdbcReplayResponder` (producer): answers each request addressed to it on the pair's replay topic from its
  `ReplayStateSource`s, page by page per tenant with the states' own docsequence, then commits the request's inbox
  row, its position and the ReplayCompleted marker in one transaction; a crash restarts the replay.
- `JdbcReplayIntake` (consumer): applies the replay topic with the STALE check shared with the live subscription,
  completes on the producer's marker (for 'every service': a marker from each source seen) and closes the
  rebuild-required incident through `RebuildRequiredSink.close`.
- `EventLogHead` on `PostgresEventLogReader`; the purge covers the replay and request topics.
- Full sync (rulings INV-Q-3603, INV-Q-3786): `JdbcConsumerReplay.fullSync` runs the service's `ReadModelClear`,
  deletes the consumer's docsequence rows for the producer across tenants (none for an 'every service' full sync,
  INV-Q-3805) and keeps the inbox, then moves the head and
  writes the request, in one transaction on the worker role bound to the platform tenant. The worker gets DELETE and
  column SELECT (consumer, source, tenant_id) on `consumer_sequence`, INSERT on `consumer_replay` and the outbox writes
  under the tenant policy; `OutboxSchema.readModelClearAccess` grants a read-model table's clear. Never BYPASSRLS.
- Record topics (coordinator ruling INV-Q-3809, INV-REQ-1221 b4): the catalogue reads `record_topic`
  (`CatalogueEntry.recordTopic`, `EventCatalogue.recordTypes()`); every inbox consumer takes those types from the
  catalogue itself, with no per-service wiring, and applies such an event on inbox dedupe by event id only, with no
  docsequence check and no held-sequence row, live and on dead-letter replay. An optional declared
  `ConsumerSubscription.recordTypes` that differs from the catalogue fails the consumer's construction (startup).
- **API change:** `JdbcInboxConsumer(subscription, catalogue, reader, …)` and
  `JdbcReplayIntake(subscription, catalogue, producer, …)` take the `EventCatalogue` (new required second parameter),
  so no consumer can run without the record-topic set. No service consumes yet, so no caller breaks in a release.
- A topic with several catalogue producers (owner ruling INV-Q-3788 A): `JdbcConsumerReplay.request` and `fullSync`
  take one request per producer under one replay id, move the topics to the head once and write one ReplayRequested
  per producer; `addressees` lists a replay's producers. Each pair completes on its producer's marker and the
  incidents close only once every addressee has completed. A full sync addressed to several producers deletes each
  addressee's docsequence rows (coordinator ruling INV-Q-3843).
- Schema: `consumer_replay` (key (replay_id, producer)), `consumer_replay_marker`, `consumer_source_seen`;
  `OutboxSchema.upgradeTo1100`.

## [1.9.0] - 2026-10-11
### Added
- Event-log purge by age per topic with a purge watermark (INV-T-3384; owner ruling INV-Q-3249; INV-REQ-1205 b3, b4,
  b6; INV-AC-2175). `EventLogSchema.watermarkDdl(purgeRole)` creates `eventlog.purge_watermark` (`topic`,
  `watermark` = highest purged `log_offset`, `updated_at`): no tenant data and no `tenant_id`, like `consumer_offset`
  (INV-Q-2954); written only by the purge role, and each topic's consumer roles read that topic's row only (a
  row-level policy per topic). `topicDdl` gives the purge role `DELETE` on the topic table and column `SELECT` on
  `log_offset` and `inserted_at` only, through its own policies beside the tenant policy of 1.8.0 (it never reads
  `envelope` or `tenant_id`), and refuses a purge role that is also a relay or consumer role.
- `EventLogPurge` / `JdbcEventLogPurge`: deletes each topic's rows older than its period in batches; the period is the
  content window (INV-ADR-2772) for a topic whose `content_topic` marker is true and the event-log retention for every
  other topic (`Retention`: default 30 days, below 7 days refused). Each batch is one statement that deletes and raises
  the topic's watermark (never lowers it), so both commit together. It never reads consumer positions or waits for a
  lagging consumer. Each run logs through SLF4J (STK-35; constant message, per-topic values as key-value pairs) and returns per-topic rows, period, cut-off and watermark. A topic
  without a `content_topic` marker is refused at construction rather than given a guessed period.
- Build: `slf4j-api` added to the version catalog (same release as `slf4j-simple`) and to this module's impl half.
- `TopicsYamlCatalogue` reads `content_topic: true|false` (INV-T-3386) into `CatalogueEntry.contentTopic` (`null`
  when absent; any other value is refused).
- `PostgresEventLogReader.read` reads the topic's watermark in the same statement as the claim; when the caller's next
  position (offset plus one) is at or below it, it throws `RebuildRequired` and returns nothing, so no consumer skips
  silently to the next surviving record. `JdbcInboxConsumer` then applies nothing and moves nothing for that topic,
  returns `PollResult.rebuildRequired = true`, and raises one `RebuildRequiredIncident` (consumer, topic, next offset,
  watermark) through its `RebuildRequiredSink` until it resumes; its other topics continue. It resumes by itself once
  the recovery path (producers' replay by scope or full sync, not in this module) has moved its offset past the
  watermark.
### Changed
- `Retention.inboxRetention` is now `max(contentWindow, eventLogRetention) + 1 day` (was `eventLogRetention + 1 day`):
  with per-topic periods a content-topic event stays in the log for the content window, so its inbox row must not
  expire sooner, or a redelivery would be applied twice. Unchanged under the defaults (both 30 days).
- **API change:** `EventLogSchema.topicDdl(topic, relayRole, consumers, purgeRole)` (new required `purgeRole`; run
  `watermarkDdl` first); `JdbcInboxConsumer(subscription, reader, claimSource, scope, handler, rebuildRequired, …)`
  (new required `RebuildRequiredSink` before `clock`). No topic table exists in any installation yet (INV-T-2890 is
  not built), so there is no upgrade script.

## [1.8.0] - 2026-10-11
### Changed
- Event-log topic tables are tenant data under row-level security (INV-T-3300, Keel INV-Q-3251 option D, INV-REQ-854,
  CS-19, ARC 4.3 step 1a). `EventLogSchema.topicDdl` creates every topic table with `tenant_id uuid NOT NULL` (no
  default; platform events under `TenantId.PLATFORM`, INV-Q-2927 option D) and ENABLE + FORCE ROW LEVEL SECURITY with
  tenancy-kernel's tenant policy, so a row without a tenant, or under a tenant other than the bound one, is refused.
  `PostgresEventBinding` inserts each row in a transaction that binds the envelope's tenant
  (`set_config('app.tenant_id', …, true)`); the relay role keeps INSERT only.
- Consumers read through the ARC 4.3 step 1a claim path, as `event_dead_letter` already does: the consumer's claim
  role has a `SELECT` policy `USING (true)` and column `SELECT` on `log_offset`, `tenant_id`, `inserted_at` only; its
  reader role has `SELECT` under the tenant policy only. `PostgresEventLogReader` claims offsets and tenants on the
  claim role, then reads `envelope` once per tenant of the batch in a transaction bound to that tenant, returning
  records in `log_offset` order (a record purged between claim and read ends the batch before it). The staleness
  `backlog` (count and `min(inserted_at)`) runs on the claim role. `topicDdl` refuses a role that is both a claim
  role and a reader (or the relay) role, because PostgreSQL ORs a role's permissive policies. No role has `BYPASSRLS`.
- **API change:** `EventLogSchema.topicDdl(topic, relayRole, consumers: List<EventLogSchema.ConsumerRoles>)` (was
  `consumerRoles: List<String>`); `PostgresEventLogReader(claimSource, envelopeSource)` (was `(dataSource)`).
  No topic table exists in any installation yet (INV-T-2890 is not built), so there is no upgrade script and no data
  migration. Event-log retention is unchanged.

## [1.7.0] - 2026-10-11
### Changed
- Per-lane rate limits are shared by every replica of a service (INV-T-3003, Keel INV-Q-2966, INV-REQ-1072,
  INV-AC-2097, INV-AC-2104): `LaneRateLimiter` counts each lane's fixed window in its row of the new
  `lane_rate_window` table of the service's schema, with one atomic upsert per permit, so N replicas together take at
  most the lane's `RateLimit.permits` per window (it was counted per worker process). The window is chosen by the
  database clock (`now()`) in the statement that takes the permit, so replica clock skew cannot raise the limit; a
  lane's window opens at its first permit. Give every replica the same `LaneLimits`. Reprocessing and backfill still
  draw only on their own limit.
- **API change:** `LaneRateLimiter(dataSource, schema, limits)` (was `(limits, clock)`; `dataSource` is the worker
  role's); `tryAcquire(lane): Boolean` / `refund(lane)` are replaced by `acquire(lane): LanePermit?` /
  `refund(permit)`, so a refund returns to the window the permit was taken from. A limit's window must be at least 1 ms.
### Added
- `lane_rate_window` (lane, window start, permits used): no tenant data, so no `tenant_id` and no RLS (CS-19
  exception, as for `consumer_offset`); `SELECT, INSERT, UPDATE, DELETE` to the worker role only, never `BYPASSRLS`.
  One row per lane: opening a lane's next window replaces the ended window's row, so nothing accumulates.
  **Schema change: run `QueueSchema.upgradeTo170(schema, workerRole)` in the service's migration before starting
  1.7.0** (additive and repeatable; new schemas get the table from `QueueSchema.ddl`).

## [1.6.0] - 2026-10-11
### Added
- `EntityRef.TENANT_WIDE` (Keel INV-Q-3165, INV-T-3175, shaped like INV-Q-3014/3018): a reserved, never-issued
  entity (the nil UUID, distinct from `EntityRef.PLATFORM`) for a platform event that concerns a whole tenant or
  group and no registered entity. It pairs only with a real tenant (`PLATFORM` still only with `TenantId.PLATFORM`);
  any other pairing is refused in `EventContext` and `Envelope`. It is stored in `outbox.entity_ref` (NOT NULL,
  forced RLS unchanged); a `com.dynokore.gst.platform.*` event leaves `entityref` out of the envelope and keeps
  `tenantid`, and the consumer maps "tenantid present, entityref absent" back to it. A domain event cannot carry it
  (refused at write with `EmissionNotPermitted` and in `Envelope`), and a domain event without `entityref` is still
  a `ContractViolation` at consume (INV-REQ-1162). A record naming the reserved id explicitly is refused.
  No schema change. tenancy-svc refusing it as an entity reference is that service's own task.

## [1.5.0] - 2026-10-11
### Changed
- Dead-letter content is restricted to tenant sessions (INV-T-2997, Keel INV-Q-2962 A6, INV-Q-3130, ARC 4.3 step 1a,
  INV-REQ-854). The worker role is granted `SELECT` on the non-content columns of `event_dead_letter` only, so it can
  no longer select `raw` (it still inserts the letter, updates the triage columns, and deletes triaged letters); the
  app role is granted `SELECT` under the existing tenant policy. `JdbcDeadLetterTriage` lists and resolves from
  metadata on the worker connection and reads `raw` (and applies the `subject` / `entity` scope filters) in the
  letter's tenant session. A letter without a tenant is triaged from metadata only: its `raw` is read by nobody, it
  cannot be replayed and a `subject` / `entity` filter never matches it.
- **API change:** `DeadLetter.raw` is now nullable, `String?` (it was `String`; `null` for a tenantless letter). `OutboxSchema.ddl` splits the worker's `FOR ALL`
  policy into per-command policies; `OutboxSchema.upgradeTo150(schema, appRole, workerRole)` does the same for an
  existing schema (idempotent; run once in the service's migration, after `upgradeTo140`).
### Added
- Triaged dead letters (`REPLAYED` or `DISCARDED`) expire: `JdbcRetentionPurge` deletes them once `resolved_at` is
  older than `Retention.contentWindow` (installation profile, default 30 days, floor 7, INV-ADR-2772), tenantless
  letters included; an open letter is never deleted (a restrictive `DELETE` policy holds even with a tenant bound).
  `PurgeResult.deadLetters` reports the count. An index on `resolved_at` serves the batches.

## [1.4.0] - 2026-10-11
### Added
- Consumer retry with bounded, jittered backoff (ARC 4.2 step 5, Keel INV-Q-2966 / INV-T-3004, INV-REQ-1164):
  `ConsumerRetryPolicy` (defaults: 8 attempts, 1 s doubling to 5 min, each wait shortened by up to 50 % at random),
  set per consumer through the new defaulted `ConsumerSubscription.retry`. `JdbcInboxConsumer` takes a defaulted
  `Clock` and jitter source (`@JvmOverloads` keeps the 1.2.0 JVM constructor).
- Delivery-attempt state on the consumer offset row (INV-Q-3108): `attempts`, `infra_failures`,
  `first_infra_failure_at`, `retry_not_before`. **Schema change: run `OutboxSchema.upgradeTo140(schema)` in the
  service's migration before starting 1.4.0** (new schemas get the columns from `ddl`; the upgrade is repeatable).
  The attempt is counted in its own transaction before each handler call and reset when the record succeeds, so the
  bound holds across restarts, a kill during the handler and replicas; a record whose recorded attempts exceed the
  bound without a reported failure is dead-lettered without calling the handler again.
- `FailureClassifier` / `FailureKind` / `DependencyUnavailable` (INV-Q-3110), set per consumer through
  `ConsumerSubscription.classifier`. The default calls a lost connection or timeout (SQLState class 08, 57P, 40001,
  40P01, transient/recoverable/timeout SQL exceptions, `IOException`, `TimeoutException`), `EventBindingUnavailable`
  and a `DependencyUnavailable` an infrastructure failure; anything else is a record failure. Infrastructure failures
  back off up to `maxDelay` without using the bound; after `ConsumerRetryPolicy.infrastructureGiveUp` (24 h) since
  the first one the record is dead-lettered.
### Changed
- Any non-fatal `Throwable` from the handler (an `Error` such as `StackOverflowError` too) is a failure; only a
  `VirtualMachineError` other than `StackOverflowError` (an `OutOfMemoryError`, say) is rethrown at once, with no
  backoff; the attempt recorded before the handler still bounds it across restarts. A `StackOverflowError` is a record
  failure.
- A handler failure still rolls the record back and is rethrown by `pollOnce`, but the next attempt waits for the
  backoff (a poll inside the wait reads nothing, so the order holds); after the last attempt the record goes to
  `event_dead_letter` with reason `handler failed, retries exhausted after N attempts: <exception class>` and the
  consumer moves on. A `ContractViolation` raised by the handler is dead-lettered at once.

## [1.3.0] - 2026-10-11
### Added
- Retention purge (INV-T-2998, Keel INV-Q-2954 A2, INV-Q-3091, INV-ADR-2772): `Retention` (content window and
  event-log retention, each default 30 days, refused below 7 days), `RetentionPurge` and `JdbcRetentionPurge`.
  It deletes published outbox rows whose `published_at` is older than the content window (an unpublished row is
  never deleted) and inbox rows whose `received_at` is older than the event-log retention plus 1 day, across
  tenants on the worker role, in batches. One retention serves all topics; the inbox has no topic column.
- `OutboxSchema.ddl` gives the worker role the purge access (a `DELETE` policy on published outbox rows, `SELECT`
  of `inbox.received_at` and `DELETE` on the inbox); `OutboxSchema.upgradeTo130` does the same for a 1.2.0
  schema (idempotent; run once in the service's migration). A restrictive `DELETE` policy keeps the worker off
  unpublished outbox rows even with a tenant bound; indexes on `outbox(published_at)` (published rows) and
  `inbox(received_at)` serve the batches. Each batch commits on its own whatever the pool's autocommit.
### Not included
- The `eventlog` topic tables are created by the event-log component's migration (`EventLogSchema`), not this
  module's service schema, and this module holds INSERT and SELECT on them only; purging them past the
  event-log retention belongs to that component.

## [1.2.0] - 2026-10-11
### Added
- Tenantless platform events (Keel INV-Q-2927 option D, INV-Q-3014, INV-Q-3018): a platform event
  type (one of INV-REQ-1226's seven `com.dynokore.gst.platform.*` events, `Envelope.isPlatformType`) may be written under
  tenancy-kernel's `TenantId.PLATFORM` with the reserved `EntityRef.PLATFORM`. Its rows keep
  `tenant_id NOT NULL` with forced RLS in `outbox`, `outbox_sequence`, `inbox` and
  `consumer_sequence` (CS-19, no schema change). The envelope leaves `tenantid` and `entityref` out
  on the wire; `EnvelopeJson` maps their absence back on consume, and the relay, consumer, replay and
  dead-letter triage bind the platform tenant through `TenantScope.bind` and its new
  `PlatformTenantBinding` (defaulted from the `TenantContext`).
### Changed
- `JdbcOutboxWriter` refuses a domain event type under the platform tenant (`EmissionNotPermitted`,
  whole batch, nothing written); `EventContext` and `Envelope` require the platform tenant and the
  platform entity to go together.
- On consume, a record without `tenantid` is a contract violation unless its type is a platform type,
  and a record naming the reserved platform id in `tenantid` is one too; `EnvelopeJson.tenantOf`
  returns the platform tenant for a tenantless platform record and `null` for the reserved id.
- `TenantScope` gains a defaulted `platform` parameter; `@JvmOverloads` keeps the 1.1.0 JVM constructor.
### Fixed
- `consumer_sequence` is keyed `(consumer, tenant_id, source, subject)`, like the producer's per-tenant
  counter: under 1.1.0's `(consumer, source, subject)` a second tenant's event with the same source and
  subject failed row-level security and stalled the consumer. `OutboxSchema.upgradeTo120` migrates a
  1.1.0 schema, keeping its rows.

## [1.1.0] - 2026-10-11
### Added
- Partition-ordered work queue (`WorkQueue`, `JdbcWorkQueue`, `QueueSchema.ddl`): claim with
  `FOR UPDATE SKIP LOCKED` across tenants through the worker role (claim columns only, no payload),
  one item at a time per (tenant, partition, lane), reclaim of abandoned claims (INV-REQ-540).
- Priority lanes `DEADLINE_NEAR`, `LIVE`, `REPROCESSING`, `BACKFILL`, `REPLAY`, strict priority, with an
  independent rate limit per lane (`LaneLimits`, `LaneRateLimiter`, injected clock) (INV-REQ-1072;
  INV-AC-2097, INV-AC-2104).
- Dead-letter triage (`DeadLetterTriage`: list, get, discard with note) and replay by scope
  (ids, tenant, consumer, topic, failure class, Doc UUID, entity, time range) to one named consumer
  (`ReplayDelivery`); `event_dead_letter` gains `status`, `resolved_at`, `note`, `replayed_to`
  (`OutboxSchema.upgradeTo110` for 1.0.0 schemas); access stays worker-only under forced RLS.
- Consumer staleness metric and one-per-episode breach (`StalenessMonitor`, `StalenessRule`,
  `StalenessBreachSink`) (INV-REQ-1165; INV-AC-2150).

## [1.0.0] - 2026-10-10
### Added
- Per-service outbox written in the command transaction; envelope (UUIDv7 id, per-key
  `docsequence`) stamped on write; emission guard against the event catalogue
  (INV-REQ-815, -1162, -1211; INV-AC-1869, -2184).
- Relay to the `EventBinding`, with the PostgreSQL event-log binding and reader; producers
  keep committing through an event-log outage and the relay catches up in per-key order
  (INV-REQ-1205, -1212; INV-AC-2185).
- Inbox consumer: inbox row, `docsequence` stale rule, offset and handler in one
  transaction; exactly-once after a kill before commit; dead letter for malformed records
  (INV-REQ-1164, -1221, -1222; INV-AC-2149, -2204, -2205).
- Test fixtures: PostgreSQL 17/18 Testcontainers fixture, flaky event-log DataSource,
  killable transaction boundary.

## [0.1.0] - 2026-09-26
### Added
- Module scaffold: Gradle project, api/impl split, `platform-libs-bom` entry
  (INV-T-2860). No interfaces yet — those land with the Wave 1 task that
  specifies them (TPL-14).
