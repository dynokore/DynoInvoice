# outbox-and-queue

Durable outbox, partition-keyed ordered queue, at-least-once delivery, idempotent consumption, dead-letter triage, replay by scope; the common event envelope of FR-K.3 and FR-M.11, stamped on every outbox write, with the per-document `sequence` (FR-AH.10); priority lanes with an independent rate limit per lane, so that reprocessing, backfill and replay never delay live or deadline-near work (FR-F.7, FR-C.8)

| | |
| --- | --- |
| Layer | L1 (SDS §18.9) |
| Embedded by | Every service with a data-plane schema — every service but `console-web` and `control-plane-svc` |
| Part used | Outbox, relay, inbox, envelope and priority lanes in all; the partition-ordered queue in `submission-svc` (FR-J.4); delivery retry, dead-letter and replay in `notification-svc` |
| Uses | tenancy-kernel |
| Wave | Wave 1: Contracts, harness and platform (SDS §25.1) |

Released on its own version with its own changelog. Services consume it only as a published artefact (FR-L.6).

## What is in 1.0.0 (INV-T-2864)

Api (`outbox-and-queue-api`, framework-free): `OutboxWriter`, `EventContext`, `OutboundEvent`, `Envelope`
(CloudEvents 1.0 fields of FR-K.3), `EmissionGuard` and `EventCatalogue`, `EventBinding`, `EventLogReader`,
`EventHandler`, `OutboxRelay`, `InboxConsumer`, `TransactionBoundary`.

Tables carry the mandatory `tenant_id` under tenancy-kernel's row-level security; the relay and consumer claim work across tenants through a worker-role policy and column grant (ARC 4.3 step 1a), then bind each item's tenant.

Impl (`outbox-and-queue`): `JdbcOutboxWriter` (stamps UUIDv7 id and per-key `docsequence` in the command
transaction), `JdbcOutboxRelay`, `PostgresEventBinding` and `PostgresEventLogReader` (the `eventlog` schema,
one table per topic), `JdbcInboxConsumer` (inbox, `docsequence` rule, offsets, dead letter), `OutboxSchema` and
`EventLogSchema` (DDL for a service's migration), `TopicsYamlCatalogue` (reads platform-contracts
`event-log/topics.yaml`).

## What 1.1.0 adds (INV-T-2865)

`WorkQueue`/`JdbcWorkQueue` (`QueueSchema.ddl`), `Lane`, `LaneLimits`, `LaneRateLimiter`, `DeadLetterTriage`/`JdbcDeadLetterTriage`,
`ReplayDelivery`/`JdbcReplayDelivery`, `StalenessMonitor`/`JdbcStalenessMonitor`, `EventLogLag`. Existing 1.0.0 schemas:
run `OutboxSchema.upgradeTo110` and `QueueSchema.ddl` in the service's migration.

Consumer retry with bounded, jittered backoff is in 1.4.0 (`ConsumerRetryPolicy`). Run `OutboxSchema.upgradeTo140` first
(it adds the attempt-state columns to `consumer_offset`). Defaults: 8 attempts; the wait after failed attempt n is 1 s
doubled each time (1, 2, 4 ... s), capped at 5 min, then shortened by a random share of up to 50 %; after the 8th failure
the record goes to `event_dead_letter`. The attempt count is stored, so it holds across restarts, kills and replicas.
Infrastructure failures (`FailureClassifier.DEFAULT`: lost connection, timeout, SQLState 08/57P/40001, an unavailable
dependency) back off to the 5 min cap without using the 8 attempts and are dead-lettered after 24 h
(`infrastructureGiveUp`). Override the policy and the classifier per consumer on `ConsumerSubscription`.

Per-lane rate limits are shared by all replicas of a service from 1.7.0 (INV-Q-2966): `LaneRateLimiter` keeps each
lane's window count in `lane_rate_window` (worker role only, no tenant data, no RLS). Existing schemas run
`QueueSchema.upgradeTo170` in the service's migration.

From 1.8.0 the event-log topic tables carry `tenant_id NOT NULL` under forced row-level security too (INV-Q-3251):
the relay inserts under the bound tenant, and each consumer has a claim role (claim columns only) and a separate
reader role (envelopes of the bound tenant only); see `EventLogSchema.topicDdl` and `PostgresEventLogReader`.

From 1.9.0 the event log is purged by age per topic (INV-Q-3249): `JdbcEventLogPurge` runs under the event-log
component's purge role and keeps the content window for topics marked `content_topic: true` in topics.yaml and the
event-log retention for the others; each delete batch raises the topic's `eventlog.purge_watermark` row in the same
transaction (`EventLogSchema.watermarkDdl`). A consumer whose next offset is at or below the watermark stops that topic
and raises a 'rebuild required' incident (`RebuildRequiredSink`) instead of skipping purged events.

Not in 1.1.0: delivery retry with backoff, inbox retention (added in 1.3.0: `Retention`, `JdbcRetentionPurge`,
`OutboxSchema.upgradeTo130`; existing schemas run `upgradeTo130` in the service's migration), rate limits shared across worker processes
(added in 1.7.0, see below), exporting the staleness metric and raising the incident (the composition root's sink), and scheduling of the
loops (service-runtime).
