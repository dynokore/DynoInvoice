# Changelog

All notable changes to `outbox-and-queue` are recorded here (FR-L.6). Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this module's release
train is its own (README.md: "own version and changelog").

## [Unreleased]

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
