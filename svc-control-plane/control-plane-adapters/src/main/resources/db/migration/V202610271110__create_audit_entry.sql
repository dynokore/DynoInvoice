-- V202610271110__create_audit_entry.sql: platform-generated DDL (TPL-12); regenerate with GeneratedMigrationsTest, never edit by hand.
-- Generated: audit-log's AuditSchema.auditEntryDdl("controlplane", "controlplane_runtime") of the platform-libs-bom 0.103.0 train.
create table controlplane.audit_entry (
    id uuid not null,
    tenant_id uuid not null,
    entity_ref text not null,
    recorded_at timestamptz not null,
    source text not null,
    subject text not null,
    doc_uuid uuid,
    transaction_date date,
    instance_id text not null,
    correlation_id text not null,
    causation_id text not null,
    actor text not null check (actor !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$|[\u0001-\u001F\u007F-\u009F]'),
    action text not null check (action !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    scope text not null check (scope !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    reason text check (reason is null or reason !~ '^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$'),
    prior_state jsonb check (prior_state is null or jsonb_typeof(prior_state) = 'object'),
    primary key (tenant_id, id)
);
create index audit_entry_tenant_time on controlplane.audit_entry (tenant_id, recorded_at);
comment on table controlplane.audit_entry is
    'retention: longest tenant VP-43 period, min 5 years; no expiry; audited purge only (INV-Q-3028)';
comment on column controlplane.audit_entry.actor is 'class: personal';
comment on column controlplane.audit_entry.reason is 'class: content';
comment on column controlplane.audit_entry.prior_state is 'class: content';
ALTER TABLE controlplane.audit_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE controlplane.audit_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_entry_tenant_isolation ON controlplane.audit_entry
    USING      (tenant_id = (select controlplane.bound_tenant()))
    WITH CHECK (tenant_id = (select controlplane.bound_tenant()));
grant select, insert on controlplane.audit_entry to controlplane_runtime;
