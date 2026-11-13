SET lock_timeout = '5s';

-- INV-T-20788, INV-REQ-554, INV-AC-20786: the local pack margin store (event-carried state transfer). One row per
-- staged calendar pack version with the safety margin the pack carried (both margin columns null when the pack had
-- none or an invalid one), and one row per activation of a pack identifier and version. Rows are immutable evidence
-- of what the pack said: the runtime role may select and insert only.
create table submission.calendar_pack_version (
    tenant_id uuid not null,
    pack_identifier text not null check (length(pack_identifier) between 1 and 200),
    pack_version text not null check (length(pack_version) between 1 and 100),
    margin_value integer null check (margin_value between 0 and 8784),
    margin_unit text null check (margin_unit in ('DAYS', 'HOURS')),
    check ((margin_value is null) = (margin_unit is null)),
    primary key (tenant_id, pack_identifier, pack_version)
);

create table submission.calendar_pack_activation (
    tenant_id uuid not null,
    pack_identifier text not null check (length(pack_identifier) between 1 and 200),
    pack_version text not null check (length(pack_version) between 1 and 100),
    effective_from date not null,
    primary key (tenant_id, pack_identifier, pack_version)
);
create index calendar_pack_activation_effective on submission.calendar_pack_activation (tenant_id, effective_from);

alter table submission.calendar_pack_version enable row level security;
alter table submission.calendar_pack_version force row level security;
create policy calendar_pack_version_tenant_isolation on submission.calendar_pack_version
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.calendar_pack_version to submission_runtime;

alter table submission.calendar_pack_activation enable row level security;
alter table submission.calendar_pack_activation force row level security;
create policy calendar_pack_activation_tenant_isolation on submission.calendar_pack_activation
    using      (tenant_id = (select submission.bound_tenant()))
    with check (tenant_id = (select submission.bound_tenant()));
grant select, insert on submission.calendar_pack_activation to submission_runtime;
