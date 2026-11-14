-- V202611141000__pack_import_and_currency.sql (INV-T-4976; INV-REQ-1078, INV-REQ-1080, INV-REQ-2468). Expand only.
-- Run as rules_migrate. The status of a regulatory pack import (IMPORTING, STAGED, FAILED with the catalogue code) and
-- the DEP-001 pack currency state. rules.pack_registry is not changed. Actors are user IDs, never roles (INV-Q-10647).

create table rules.pack_import (
    tenant_id     uuid        not null,
    pack_type     text        not null check (pack_type !~ '^\s*$|[[:cntrl:]]' and char_length(pack_type) <= 40),
    pack_version  text        not null check (pack_version !~ '^\s*$|[[:cntrl:]]' and char_length(pack_version) <= 16),
    status        text        not null check (status in ('IMPORTING', 'STAGED', 'FAILED')),
    failure_code  text        null check (failure_code in ('DEP-002', 'DEP-003', 'RUL-005')),
    bundle_hash   text        not null check (bundle_hash !~ '^\s*$|[[:cntrl:]]' and char_length(bundle_hash) <= 128),
    actor         text        not null check (actor !~ '^\s*$|[[:cntrl:]]'),
    started_at    timestamptz not null default clock_timestamp(),
    finished_at   timestamptz null,
    primary key (tenant_id, pack_type, pack_version),
    -- a failed import names its code; an import that did not fail has none; one that finished has its time
    constraint pack_import_outcome check (
        ((status = 'FAILED') = (failure_code is not null))
        and ((status = 'IMPORTING') = (finished_at is null)))
);

alter table rules.pack_import enable row level security;
alter table rules.pack_import force row level security;
create policy pack_import_read on rules.pack_import for select
    using (tenant_id = (select rules.bound_tenant()));
create policy pack_import_insert on rules.pack_import for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy pack_import_update on rules.pack_import for update
    using (tenant_id = (select rules.bound_tenant()))
    with check (tenant_id = (select rules.bound_tenant()));
revoke all on rules.pack_import from public;
grant select, insert, update on rules.pack_import to rules_runtime;

-- The DEP-001 state of the installation: one row under the platform tenant. last_reached_on is the calendar day the
-- feed was last reached or a bundle last imported; the other columns are the last published tuple of the condition
-- (PackCurrencyChanged is published only when the tuple changes).
create table rules.pack_currency_state (
    tenant_id              uuid    not null primary key,
    last_reached_on        date    not null,
    published              boolean not null default false,
    raised                 boolean not null default false,
    pack_identifier        text    null,
    pack_version           text    null,
    days_to_effective_date integer null check (days_to_effective_date is null or days_to_effective_date >= 0),
    days_since_last_reached integer null check (days_since_last_reached is null or days_since_last_reached >= 0),
    evaluated_on           date    null
);
alter table rules.pack_currency_state enable row level security;
alter table rules.pack_currency_state force row level security;
create policy pack_currency_state_read on rules.pack_currency_state for select
    using (tenant_id = (select rules.bound_tenant()));
create policy pack_currency_state_insert on rules.pack_currency_state for insert
    with check (tenant_id = (select rules.bound_tenant()));
create policy pack_currency_state_update on rules.pack_currency_state for update
    using (tenant_id = (select rules.bound_tenant()))
    with check (tenant_id = (select rules.bound_tenant()));
revoke all on rules.pack_currency_state from public;
grant select, insert, update on rules.pack_currency_state to rules_runtime;
