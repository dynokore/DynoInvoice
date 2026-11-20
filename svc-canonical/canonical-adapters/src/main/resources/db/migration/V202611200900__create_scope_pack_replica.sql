-- V202611200900__create_scope_pack_replica.sql (INV-T-25433; INV-REQ-772, INV-REQ-658 as the datum).
-- The scope pack versions canonical-svc has received by event (RulePackStaged, RulePackActivated), kept as platform
-- reference data for the dry-run sample: no tenant_id, no row-level security. A version is immutable once stored.
CREATE TABLE canonical.scope_pack_version (
    pack_identifier text NOT NULL CHECK (length(btrim(pack_identifier)) BETWEEN 1 AND 128),
    pack_version text NOT NULL CHECK (length(btrim(pack_version)) BETWEEN 1 AND 64),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (pack_identifier, pack_version)
);
COMMENT ON TABLE canonical.scope_pack_version IS 'platform-scoped: no tenant data';
GRANT SELECT, INSERT ON canonical.scope_pack_version TO canonical_runtime;

CREATE TABLE canonical.scope_pack_function (
    pack_identifier text NOT NULL,
    pack_version text NOT NULL,
    document_type_code smallint NOT NULL CHECK (document_type_code IN (380, 381)),
    document_function text NOT NULL CHECK (document_function IN ('BILL_FOR_PAYMENT', 'ADJUSTMENT', 'NOT_A_BILL')),
    PRIMARY KEY (pack_identifier, pack_version, document_type_code, document_function),
    FOREIGN KEY (pack_identifier, pack_version) REFERENCES canonical.scope_pack_version (pack_identifier, pack_version)
);
COMMENT ON TABLE canonical.scope_pack_function IS 'platform-scoped: no tenant data';
GRANT SELECT, INSERT ON canonical.scope_pack_function TO canonical_runtime;

-- No foreign key: the two topics have no order guarantee, so an activation may arrive before its staged version.
CREATE TABLE canonical.scope_pack_activation (
    pack_identifier text NOT NULL CHECK (length(btrim(pack_identifier)) BETWEEN 1 AND 128),
    pack_version text NOT NULL CHECK (length(btrim(pack_version)) BETWEEN 1 AND 64),
    effective_from date NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (pack_identifier, pack_version)
);
COMMENT ON TABLE canonical.scope_pack_activation IS 'platform-scoped: no tenant data';
GRANT SELECT, INSERT ON canonical.scope_pack_activation TO canonical_runtime;
