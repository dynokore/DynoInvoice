SET lock_timeout = '5s';

-- The report id of a break-glass action reported by another service as an event (INV-Q-9117 A); null for rows recorded by
-- the synchronous path. A report id is held once per tenant; a null does not collide.
ALTER TABLE access.break_glass_action
    ADD COLUMN report_ref text CHECK (report_ref IS NULL OR length(report_ref) BETWEEN 1 AND 64),
    ADD CONSTRAINT break_glass_action_tenant_report_ref_key UNIQUE (tenant_id, report_ref);
