-- V202611021300__add_verification_key_use.sql (INV-T-4154; INV-Q-16473 A)
-- A verification key now says what it verifies: machine access tokens (getVerificationKeys, the gateway) or the
-- tenant-scoped operator sessions (OperatorSessionKeysChanged, the services' authority replicas). Existing rows are
-- machine keys.

ALTER TABLE access.verification_key
    ADD COLUMN use text NOT NULL DEFAULT 'MACHINE_ACCESS'
        CONSTRAINT verification_key_use_check CHECK (use IN ('MACHINE_ACCESS', 'OPERATOR_SESSION'));
