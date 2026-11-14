SET lock_timeout = '5s';

-- INV-T-22207, INV-REQ-792, INV-AC-22206: the local completeness rows (event-carried state transfer, ruled case 4).
-- One row per feed control and one row per window part of an entity and period, each with the highest stateVersion
-- seen; a row changes only for a greater version. The runtime role may select, insert and update only.
create table submission.feed_control_state (
    tenant_id uuid not null,
    feed text not null check (length(feed) between 1 and 200),
    entity_ref text not null check (length(entity_ref) between 1 and 200),
    period_ref text not null check (length(period_ref) between 1 and 200),
    control text not null check (length(control) between 1 and 200),
    failing boolean not null,
    state_version bigint not null check (state_version >= 0),
    primary key (tenant_id, feed, entity_ref, period_ref, control)
);

create table submission.window_part_state (
    tenant_id uuid not null,
    entity_ref text not null check (length(entity_ref) between 1 and 200),
    period_ref text not null check (length(period_ref) between 1 and 200),
    part_from date not null,
    part_to date not null,
    state text not null check (state in ('OPEN', 'CLOSED', 'LATE')),
    state_version bigint not null check (state_version >= 0),
    primary key (tenant_id, entity_ref, period_ref, part_from, part_to)
);

alter table submission.feed_control_state enable row level security;
alter table submission.feed_control_state force row level security;
create policy feed_control_state_tenant_isolation on submission.feed_control_state
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.feed_control_state to submission_runtime;

alter table submission.window_part_state enable row level security;
alter table submission.window_part_state force row level security;
create policy window_part_state_tenant_isolation on submission.window_part_state
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert, update on submission.window_part_state to submission_runtime;
