-- V22__partition_deletion_operation.sql (INV-T-22971; INV-REQ-639, INV-REQ-1011, INV-AC-22964; API-17).
-- The durable record of one deleteAtRetentionEnd operation: the status resource getPartitionDeletion reads and the
-- single-flight claim on the partition. The starter is the acting user ID, never a role. A RUNNING or SUCCEEDED row
-- holds the partition (partial unique index); a FAILED row never blocks a retry. Only a RUNNING row may change; the
-- runtime login holds a column-level UPDATE and no DELETE.

create table evidence.partition_deletion_operation (
    operation_id uuid not null,
    tenant_id uuid not null,
    partition_id text not null references evidence.retention_partition (partition_id),
    starter_user_id text not null check (starter_user_id <> ''),
    state text not null check (state in ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at timestamptz not null,
    completed_at timestamptz,
    error_code text check (char_length(error_code) <= 100),
    primary key (tenant_id, operation_id),
    check ((state = 'RUNNING') = (completed_at is null))
);
create unique index partition_deletion_operation_single_flight
    on evidence.partition_deletion_operation (tenant_id, partition_id) where state in ('RUNNING', 'SUCCEEDED');
ALTER TABLE evidence.partition_deletion_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence.partition_deletion_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence.partition_deletion_operation
    USING      (tenant_id = (select evidence.bound_tenant()))
    WITH CHECK (tenant_id = (select evidence.bound_tenant()));
grant select, insert, update (state, completed_at, error_code)
    on evidence.partition_deletion_operation to evidence_runtime;

CREATE OR REPLACE FUNCTION evidence.partition_deletion_operation_running_only() RETURNS trigger AS $$
BEGIN
    IF OLD.state <> 'RUNNING' THEN
        RAISE EXCEPTION 'CS-22: % is final in state %; % is refused', TG_TABLE_NAME, OLD.state, TG_OP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER partition_deletion_operation_running_only
    BEFORE UPDATE ON evidence.partition_deletion_operation
    FOR EACH ROW EXECUTE FUNCTION evidence.partition_deletion_operation_running_only();
