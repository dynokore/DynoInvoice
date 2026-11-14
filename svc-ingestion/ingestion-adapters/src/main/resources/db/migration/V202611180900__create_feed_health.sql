-- V202611180900__create_feed_health.sql (INV-T-22216; INV-REQ-815, INV-Q-20752 case 2)
-- The feed-health state and the producer counter of ingestion-svc: one row per (tenant, feed, entity, period,
-- control). state_version starts at 1 and rises by one for every save of the key, in the transaction that appends the
-- FeedHealthChanged event to the outbox. Row-level security is forced with the signed-binding policy of the other
-- tables; the runtime role never deletes.
create table ingestion.feed_health_state (
    tenant_id uuid not null,
    feed text not null,
    entity_ref text not null,
    period text not null,
    control text not null check (control in ('SEQUENCE', 'COUNT', 'VALUE', 'WATERMARK', 'MANIFEST')),
    state text not null check (state in ('FAILED', 'CLEARED')),
    state_version bigint not null check (state_version >= 1),
    primary key (tenant_id, feed, entity_ref, period, control)
);
ALTER TABLE ingestion.feed_health_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion.feed_health_state FORCE ROW LEVEL SECURITY;
CREATE POLICY feed_health_state_tenant_isolation ON ingestion.feed_health_state
    USING      (tenant_id = (select ingestion.bound_tenant()))
    WITH CHECK (tenant_id = (select ingestion.bound_tenant()));
grant select, insert, update on ingestion.feed_health_state to ingestion_runtime;
