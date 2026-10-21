-- V202610210900__create_identity_triple.sql (INV-T-4942; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as masterdata_migrate (never at start-up). One row is one effective-dated
-- identity triple of a registered entity under a legal constitution (INV-REQ-310): the triple is resolved per
-- (entity, constitution), never from one global setting. The supplier triple (sender_id, supplier_id, supplier_gstn)
-- and the person who proposed and the person who confirmed the change (proposed_by, confirmed_by, confirmed_role) are
-- different columns and neither is computed from the other (INV-REQ-803). The runtime login never deletes: a repeat of
-- (entity, constitution, effective_from) replaces the row.

CREATE TABLE masterdata.identity_triple (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    entity_ref      text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    constitution    text        NOT NULL CHECK (length(constitution) BETWEEN 1 AND 40),
    sender_id       text        NOT NULL CHECK (length(sender_id) BETWEEN 1 AND 64),
    supplier_id     text        NOT NULL CHECK (length(supplier_id) BETWEEN 1 AND 64),
    supplier_gstn   text        NOT NULL CHECK (length(supplier_gstn) BETWEEN 1 AND 20),
    effective_from  date        NOT NULL,
    proposed_by     text        NOT NULL CHECK (length(proposed_by) BETWEEN 1 AND 64),
    confirmed_by    text        NOT NULL CHECK (length(confirmed_by) BETWEEN 1 AND 64),
    confirmed_role  text        NOT NULL CHECK (confirmed_role IN ('entity.admin', 'tenant.admin', 'tax.approver')),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, entity_ref, constitution, effective_from),
    CHECK (lower(btrim(confirmed_by)) <> lower(btrim(proposed_by)))           -- never the same person (INV-REQ-1007)
);

ALTER TABLE masterdata.identity_triple ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.identity_triple FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.identity_triple
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.identity_triple TO masterdata_runtime;

-- A proposed change (INV-REQ-1007). `configureIdentityTriple` writes a PENDING_CONFIRMATION row and puts nothing in
-- force: the resolver reads identity_triple only, so a document resolved meanwhile carries the previous triple. The
-- confirmation by a different person moves the row to CONFIRMED and writes the identity_triple version. The proposer and
-- the confirmer are separate columns and never the same person.

CREATE TABLE masterdata.identity_triple_change (
    id              uuid        PRIMARY KEY,                                  -- UUIDv7, assigned by the application
    tenant_id       uuid        NOT NULL,                                     -- FR-G.5
    change_ref      text        NOT NULL CHECK (length(change_ref) BETWEEN 1 AND 40),
    entity_ref      text        NOT NULL CHECK (length(entity_ref) BETWEEN 1 AND 40),
    constitution    text        NOT NULL CHECK (length(constitution) BETWEEN 1 AND 40),
    sender_id       text        NOT NULL CHECK (length(sender_id) BETWEEN 1 AND 64),
    supplier_id     text        NOT NULL CHECK (length(supplier_id) BETWEEN 1 AND 64),
    supplier_gstn   text        NOT NULL CHECK (length(supplier_gstn) BETWEEN 1 AND 20),
    effective_from  date        NOT NULL,
    state           text        NOT NULL CHECK (state IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'SUPERSEDED')),
    proposed_by     text        NOT NULL CHECK (length(proposed_by) BETWEEN 1 AND 64),
    confirmed_by    text        CHECK (length(confirmed_by) BETWEEN 1 AND 64),
    confirmed_role  text        CHECK (confirmed_role IN ('entity.admin', 'tenant.admin', 'tax.approver')),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, entity_ref, change_ref),
    CHECK ((state = 'CONFIRMED') = (confirmed_by IS NOT NULL AND confirmed_role IS NOT NULL)),
    CHECK (confirmed_by IS NULL OR lower(btrim(confirmed_by)) <> lower(btrim(proposed_by)))  -- never the same person
);

ALTER TABLE masterdata.identity_triple_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.identity_triple_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.identity_triple_change
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.identity_triple_change TO masterdata_runtime;
