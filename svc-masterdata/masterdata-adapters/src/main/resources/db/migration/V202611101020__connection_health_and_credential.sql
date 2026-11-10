SET lock_timeout = '5s';

-- INV-T-18776 (INV-REQ-836, INV-REQ-837; INV-Q-10027; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up).
-- call_record:         every recorded call of a connection (the health figures are computed from these; never raw error text).
-- connection_state:    the state last requested as ConnectionStatusChanged for a connection (this instance's memory, durable).
-- credential_state:    the current and previous credential reference of a connection and the overlap end of the last rotation.
-- credential_reminder: which (connection, credential, offset) reminders were already requested.
-- connection_expiry_tenants(): tenant ids only (reads masterdata.credential_expiry of INV-T-19568), for the expiry-check job, which runs bound to no tenant.

CREATE TABLE masterdata.call_record (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    record_ref     text        NOT NULL CHECK (length(record_ref) BETWEEN 1 AND 40),   -- the connection
    called_at      timestamptz NOT NULL,
    latency_millis bigint      NOT NULL CHECK (latency_millis >= 0),
    failure_class  text        CHECK (failure_class IN ('UNREACHABLE', 'AUTHENTICATION_REFUSED', 'UNEXPECTED_RESPONSE')),
    failure_detail text        CHECK (failure_detail ~ '^[A-Z][A-Z0-9_-]{1,31}$'),     -- a code only (INV-Q-2944)
    CHECK (failure_detail IS NULL OR failure_class IS NOT NULL)
);
CREATE INDEX call_record_by_connection ON masterdata.call_record (tenant_id, record_ref, called_at);

CREATE TABLE masterdata.connection_state (
    tenant_id  uuid        NOT NULL,
    record_ref text        NOT NULL CHECK (length(record_ref) BETWEEN 1 AND 40),
    state      text        NOT NULL CHECK (state IN ('UP', 'DEGRADED', 'DOWN')),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, record_ref)
);

CREATE TABLE masterdata.credential_state (
    tenant_id         uuid        NOT NULL,
    record_ref        text        NOT NULL CHECK (length(record_ref) BETWEEN 1 AND 40),
    current_ref       text        NOT NULL CHECK (length(current_ref) BETWEEN 1 AND 100),
    previous_ref      text        CHECK (length(previous_ref) BETWEEN 1 AND 100),
    overlap_end       timestamptz,
    rotated_at        timestamptz,
    last_previous_ref text        CHECK (length(last_previous_ref) BETWEEN 1 AND 100),
    last_overlap_end  timestamptz,
    PRIMARY KEY (tenant_id, record_ref)
);

CREATE TABLE masterdata.credential_reminder (
    tenant_id      uuid        NOT NULL,
    record_ref     text        NOT NULL CHECK (length(record_ref) BETWEEN 1 AND 40),
    credential_ref text        NOT NULL CHECK (length(credential_ref) BETWEEN 1 AND 100),
    offset_days    integer     NOT NULL CHECK (offset_days >= 1),
    reminded_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, record_ref, credential_ref, offset_days)
);

ALTER TABLE masterdata.call_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.call_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.call_record
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.connection_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.connection_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.connection_state
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.credential_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.credential_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.credential_state
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));
ALTER TABLE masterdata.credential_reminder ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.credential_reminder FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.credential_reminder
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT ON masterdata.call_record TO masterdata_runtime;               -- a recorded call is never changed
GRANT SELECT, INSERT, UPDATE ON masterdata.connection_state TO masterdata_runtime;
GRANT SELECT, INSERT, UPDATE ON masterdata.credential_state TO masterdata_runtime;
GRANT SELECT, INSERT ON masterdata.credential_reminder TO masterdata_runtime;

-- The expiry-check job runs bound to no tenant: this function returns tenant ids and nothing else; the check then runs
-- under each tenant's own binding. Modelled on masterdata.stale_record_tenants (INV-Q-18051 A).
grant usage on schema masterdata to masterdata_sys;
create policy expiry_sys_select on masterdata.credential_expiry for select to masterdata_sys using (true);
grant select (tenant_id) on masterdata.credential_expiry to masterdata_sys;

create function masterdata.connection_expiry_tenants()
returns table (expiry_tenant uuid)
language sql stable security definer set search_path = pg_catalog, masterdata, pg_temp as $$
    select distinct e.tenant_id from masterdata.credential_expiry e
$$;
grant create on schema masterdata to masterdata_sys;
alter function masterdata.connection_expiry_tenants() owner to masterdata_sys;
revoke create on schema masterdata from masterdata_sys;
revoke all on function masterdata.connection_expiry_tenants() from public;
revoke all on function masterdata.connection_expiry_tenants() from masterdata_sys;
grant execute on function masterdata.connection_expiry_tenants() to masterdata_runtime;
