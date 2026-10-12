# Changelog

All notable changes to `outbox-and-queue` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]
### Changed (breaking; released as the MAJOR of the eventlog_sys task, INV-T-3979)
- One runtime login per service (INV-T-3978, stage 1; owner rulings INV-Q-3951, INV-Q-3953, INV-Q-3965;
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
- Transitional until stage 2: `OutboxSchema.fullSyncWorkerAccess` and `readModelClearAccess` keep the full sync on the
  former worker role pending the owner's ruling on the read-model clear.

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
