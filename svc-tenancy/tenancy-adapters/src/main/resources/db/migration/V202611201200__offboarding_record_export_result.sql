-- V202611201200: the export result of the offboarding record (INV-REQ-877; INV-T-22966, INV-AC-22960). The result event
-- of the export moves a REQUESTED record to COMPLETED or FAILED: tenancy_runtime may update only the four columns of
-- the result (column-level UPDATE, no DELETE; NIST SP 800-53 AC-6), and a trigger refuses any update of a record whose
-- state is COMPLETED or FAILED, so a terminal state is immutable (INV-Q-5025). This replaces the rule of
-- V202611091100 that a record is never updated. Run by tenancy_migrate.
SET lock_timeout = '5s';

ALTER TABLE tenancy.offboarding_record
    ADD COLUMN export_failure_reason text CHECK (char_length(export_failure_reason) <= 500);

CREATE FUNCTION tenancy.offboarding_record_terminal_immutable() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF OLD.state IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'tenancy.offboarding_record % is % and cannot change', OLD.offboarding_ref, OLD.state
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER offboarding_record_terminal_immutable
    BEFORE UPDATE ON tenancy.offboarding_record
    FOR EACH ROW EXECUTE FUNCTION tenancy.offboarding_record_terminal_immutable();

GRANT UPDATE (state, export_state, export_contents, export_failure_reason) ON tenancy.offboarding_record TO tenancy_runtime;
