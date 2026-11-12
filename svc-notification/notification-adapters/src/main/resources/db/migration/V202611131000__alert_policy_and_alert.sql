-- V202611131000__alert_policy_and_alert.sql (INV-T-20814, INV-REQ-1370; slice O1c-1).
-- Expand-only: two new tables, nothing existing is altered or dropped. Coordinator ruling (delegated, owner 2026-10-14),
-- round h (alert policy storage) and round i (backlog alert store): the tables follow the contract schemas AlertPolicy
-- and Alert of notification/openapi.yaml. Thresholds are tenant data set through the API: no seed row, no default value.

create table notification.alert_policy (
    tenant_id uuid not null,
    entity_ref text not null,
    alert_class text not null,
    recipients jsonb not null,
    channels jsonb not null,
    escalation_tiers jsonb,
    threshold_days integer,
    sole_operator_acknowledgement boolean,
    accountable_person text,
    acknowledgement_effective_from date,
    acknowledgement_recorded_by text,
    acknowledgement_recorded_at timestamptz,
    updated_at timestamptz not null,
    max_queue_depth integer,
    max_oldest_unsent_age_seconds bigint,
    max_failure_ratio numeric,
    max_unacknowledged_count integer,
    primary key (tenant_id, entity_ref, alert_class),
    -- the five acknowledgement columns are all null or all set
    constraint alert_policy_acknowledgement_complete check (
        (sole_operator_acknowledgement is null and accountable_person is null
            and acknowledgement_effective_from is null and acknowledgement_recorded_by is null
            and acknowledgement_recorded_at is null)
        or (sole_operator_acknowledgement is not null and accountable_person is not null
            and acknowledgement_effective_from is not null and acknowledgement_recorded_by is not null
            and acknowledgement_recorded_at is not null)),
    constraint alert_policy_threshold_days_range check (threshold_days is null or threshold_days between 0 and 365),
    constraint alert_policy_max_queue_depth_min check (max_queue_depth is null or max_queue_depth >= 1),
    constraint alert_policy_max_oldest_unsent_age_min check (
        max_oldest_unsent_age_seconds is null or max_oldest_unsent_age_seconds >= 1),
    constraint alert_policy_max_failure_ratio_range check (max_failure_ratio is null or max_failure_ratio between 0 and 1),
    constraint alert_policy_max_unacknowledged_min check (
        max_unacknowledged_count is null or max_unacknowledged_count >= 1)
);
comment on column notification.alert_policy.entity_ref is 'class: operational';
comment on column notification.alert_policy.alert_class is 'class: operational';
comment on column notification.alert_policy.recipients is 'class: operational';
comment on column notification.alert_policy.channels is 'class: operational';
comment on column notification.alert_policy.escalation_tiers is 'class: operational';
comment on column notification.alert_policy.threshold_days is 'class: operational';
comment on column notification.alert_policy.sole_operator_acknowledgement is 'class: operational';
comment on column notification.alert_policy.accountable_person is 'class: operational';
comment on column notification.alert_policy.acknowledgement_effective_from is 'class: operational';
comment on column notification.alert_policy.acknowledgement_recorded_by is 'class: operational';
comment on column notification.alert_policy.acknowledgement_recorded_at is 'class: operational';
comment on column notification.alert_policy.updated_at is 'class: operational';
comment on column notification.alert_policy.max_queue_depth is 'class: operational';
comment on column notification.alert_policy.max_oldest_unsent_age_seconds is 'class: operational';
comment on column notification.alert_policy.max_failure_ratio is 'class: operational';
comment on column notification.alert_policy.max_unacknowledged_count is 'class: operational';

create table notification.alert (
    tenant_id uuid not null,
    alert_id uuid not null,
    entity_ref text not null,
    alert_class text not null,
    state text not null,
    raised_at timestamptz not null,
    "trigger" text,
    case_id uuid,
    alert_ref text,
    task_ref text,
    current_tier integer,
    period_ref text,
    acknowledged_at timestamptz,
    acknowledged_by text,
    snoozed_until timestamptz,
    primary key (tenant_id, alert_id),
    -- written to fail closed on a null: a null trigger never satisfies the left side
    constraint alert_case_id_iff_privacy_notice check (coalesce("trigger" = 'PRIVACY_NOTICE_DUE', false) = (case_id is not null)),
    constraint alert_ref_iff_accreditation check ((alert_class = 'ACCREDITATION') = (alert_ref is not null)),
    constraint alert_task_ref_iff_accreditation check ((alert_class = 'ACCREDITATION') = (task_ref is not null)),
    constraint alert_snoozed_until_only_snoozed check (snoozed_until is null or state = 'SNOOZED'),
    constraint alert_current_tier_min check (current_tier is null or current_tier >= 1)
);
-- One open alert per entity, class, trigger and period. A null trigger and a null period_ref are each one key value
-- (coalesce to the empty string), so a repeated measurement does not raise a second alert while the first is open.
create unique index alert_one_open_per_key on notification.alert
    (tenant_id, entity_ref, alert_class, coalesce("trigger", ''), coalesce(period_ref, ''))
    where state in ('RAISED', 'SNOOZED');
comment on column notification.alert.entity_ref is 'class: operational';
comment on column notification.alert.alert_class is 'class: operational';
comment on column notification.alert.state is 'class: operational';
comment on column notification.alert.raised_at is 'class: operational';
comment on column notification.alert."trigger" is 'class: operational';
comment on column notification.alert.case_id is 'class: operational';
comment on column notification.alert.alert_ref is 'class: operational';
comment on column notification.alert.task_ref is 'class: operational';
comment on column notification.alert.current_tier is 'class: operational';
comment on column notification.alert.period_ref is 'class: operational';
comment on column notification.alert.acknowledged_at is 'class: operational';
comment on column notification.alert.acknowledged_by is 'class: operational (the acknowledging user ID, never a role)';
comment on column notification.alert.snoozed_until is 'class: operational';

alter table notification.alert_policy enable row level security;
alter table notification.alert_policy force row level security;
create policy alert_policy_tenant_isolation on notification.alert_policy
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));
alter table notification.alert enable row level security;
alter table notification.alert force row level security;
create policy alert_tenant_isolation on notification.alert
    using      (tenant_id = (select notification.bound_tenant()))
    with check (tenant_id = (select notification.bound_tenant()));

grant usage on schema notification to notification_runtime;
grant select on notification.alert_policy to notification_runtime;
grant select, insert on notification.alert to notification_runtime;
