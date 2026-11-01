SET lock_timeout = '5s';

-- V202611021000__reissue_first_administrator_invitation.sql (INV-T-8282; INV-Q-8232 A, INV-Q-15859 A, INV-Q-15861 A)
-- Run only by the service's migration Job as access_migrate (never at start-up). The reissue of a first tenant.admin
-- invitation stores the Idempotency-Key and a request fingerprint (SHA-256 of the tenantRef, a colon and the requested
-- expiry as Instant.toString()) on the invitation row of the grant, so that a repeat with the same key is recognised and
-- replays the grant data. The row holds the latest reissue key only. No response body and no code is stored. Both
-- columns stay NULL until the invitation is reissued. The grants of the table to access_runtime stay as they are
-- (SELECT, INSERT, UPDATE; no DELETE) and forced row-level security is unchanged.
ALTER TABLE access.first_administrator_invitation
    ADD COLUMN reissue_key uuid,
    ADD COLUMN reissue_fingerprint text CHECK (reissue_fingerprint IS NULL OR length(reissue_fingerprint) BETWEEN 1 AND 128),
    ADD CONSTRAINT first_administrator_invitation_reissue_pair
        CHECK ((reissue_key IS NULL) = (reissue_fingerprint IS NULL)),
    ADD CONSTRAINT first_administrator_invitation_reissue_key_key UNIQUE (tenant_id, reissue_key);
