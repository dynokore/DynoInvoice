-- First administrator invitation (TPL-12, CS-19, INV-AC-8224, INV-AC-8281, INV-REQ-906 b2)
-- provisionTenant now asks access-svc for the first tenant.admin grant after tenancy-svc created the tenant, and the
-- invitation reference exists only once access-svc has answered: the column becomes nullable. The outcome records a
-- 409 of access-svc. The invitation code is stored nowhere. A reissue attempt keeps the expiry fixed when the call
-- first arrived, so a retry with the same Idempotency-Key sends access-svc the same request.

ALTER TABLE controlplane.tenant_provisioning_request ALTER COLUMN invitation_ref DROP NOT NULL;
ALTER TABLE controlplane.tenant_provisioning_request
    ADD COLUMN first_administrator_outcome text
        CHECK (first_administrator_outcome IN ('INVITED', 'TENANT_ALREADY_HAS_ADMINISTRATOR'));

CREATE TABLE controlplane.first_invitation_reissue (
    idempotency_key       uuid        NOT NULL,
    contract_reference    text        NOT NULL CHECK (length(contract_reference) BETWEEN 1 AND 64),
    invitation_expires_at timestamptz NOT NULL,
    invitation_ref        text        CHECK (length(invitation_ref) BETWEEN 1 AND 64),
    recorded_by           text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 128),
    recorded_at           timestamptz NOT NULL,
    PRIMARY KEY (recorded_by, idempotency_key)
);
COMMENT ON TABLE controlplane.first_invitation_reissue IS 'platform-scoped: no tenant data';

GRANT UPDATE (invitation_ref, first_administrator_outcome) ON controlplane.tenant_provisioning_request
    TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.first_invitation_reissue TO controlplane_runtime;
GRANT UPDATE (invitation_ref) ON controlplane.first_invitation_reissue TO controlplane_runtime;
