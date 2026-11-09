-- V202611091300: the lifecycle of a pending election change (INV-REQ-370, INV-REQ-422, INV-REQ-505, INV-REQ-1284;
-- owner answers INV-Q-10113 to INV-Q-10121, option A of each). A PENDING change can also be closed WITHDRAWN (by its
-- requester) or DECLINED (by another person), and an entity has at most one PENDING change per variation point.
-- Existing rows stay valid: every one is PENDING or APPROVED and has no closure.
SET lock_timeout = '5s';

ALTER TABLE tenancy.election_change DROP CONSTRAINT election_change_state_check;
ALTER TABLE tenancy.election_change
    ADD CONSTRAINT election_change_state_check
        CHECK (state IN ('PENDING', 'APPROVED', 'WITHDRAWN', 'DECLINED'));

ALTER TABLE tenancy.election_change
    ADD COLUMN closed_by text CHECK (length(closed_by) BETWEEN 1 AND 128),
    ADD COLUMN closed_at timestamptz;

ALTER TABLE tenancy.election_change
    ADD CONSTRAINT election_change_closure_check
        CHECK ((state IN ('WITHDRAWN', 'DECLINED')) = (closed_by IS NOT NULL AND closed_at IS NOT NULL)
               AND (closed_by IS NULL) = (closed_at IS NULL));

CREATE UNIQUE INDEX election_change_one_pending
    ON tenancy.election_change (tenant_id, entity_ref, variation_point)
    WHERE state = 'PENDING';
