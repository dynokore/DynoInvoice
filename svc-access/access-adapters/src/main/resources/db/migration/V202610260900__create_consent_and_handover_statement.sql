-- V202610230900__create_consent_and_handover_statement.sql (INV-T-4939; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: a consent is withdrawn by status and stays as the record of what was granted (INV-REQ-865 behaviour 4).

-- An agent's consent for one client entity (INV-REQ-713, INV-REQ-912). The three scope items are separate columns that
-- default to false: none is granted unless the client signatory chose it (INV-REQ-1271).
CREATE TABLE access.consent (
    id                       uuid        PRIMARY KEY,
    tenant_id                uuid        NOT NULL,
    consent_ref              text        NOT NULL CHECK (length(consent_ref) BETWEEN 1 AND 64),
    client_entity_ref        text        NOT NULL CHECK (length(client_entity_ref) BETWEEN 1 AND 64),
    agent_ref                text        NOT NULL CHECK (length(agent_ref) BETWEEN 1 AND 64),
    submit_invoice_data      boolean     NOT NULL DEFAULT false,
    file_gst_return          boolean     NOT NULL DEFAULT false,
    mapping_approval         boolean     NOT NULL DEFAULT false,
    valid_from               date        NOT NULL,
    authorising_person_email text        NOT NULL CHECK (length(authorising_person_email) BETWEEN 3 AND 254),
    status                   text        NOT NULL CHECK (status IN ('LIVE', 'WITHDRAWN')),
    handover_statement_ref   text        CHECK (length(handover_statement_ref) BETWEEN 1 AND 64),
    created_at               timestamptz NOT NULL,
    created_by               text        NOT NULL,
    updated_at               timestamptz NOT NULL,
    updated_by               text        NOT NULL,
    version                  bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, consent_ref),
    CHECK ((status = 'WITHDRAWN') = (handover_statement_ref IS NOT NULL))
);
-- At most one live consent per client entity: a replica keys the consent record on (tenant, client entity).
CREATE UNIQUE INDEX consent_one_live_per_client ON access.consent (tenant_id, client_entity_ref) WHERE status = 'LIVE';

-- The handover statement of a withdrawal (INV-REQ-955): the documents ingested but unsubmitted, or the marker that the
-- list was unavailable when the statement was produced (INV-Q-4900).
CREATE TABLE access.handover_statement (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    statement_ref     text        NOT NULL CHECK (length(statement_ref) BETWEEN 1 AND 64),
    consent_ref       text        NOT NULL CHECK (length(consent_ref) BETWEEN 1 AND 64),
    client_entity_ref text        NOT NULL CHECK (length(client_entity_ref) BETWEEN 1 AND 64),
    agent_ref         text        NOT NULL CHECK (length(agent_ref) BETWEEN 1 AND 64),
    document_list     text        NOT NULL CHECK (document_list IN ('LISTED', 'UNAVAILABLE')),
    produced_at       timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    created_by        text        NOT NULL,
    UNIQUE (tenant_id, statement_ref),
    UNIQUE (tenant_id, consent_ref)
);

CREATE TABLE access.handover_statement_document (
    id              uuid NOT NULL PRIMARY KEY,
    tenant_id       uuid NOT NULL,
    statement_ref   text NOT NULL CHECK (length(statement_ref) BETWEEN 1 AND 64),
    ordinal         integer NOT NULL CHECK (ordinal >= 0),
    document_ref    text NOT NULL CHECK (length(document_ref) BETWEEN 1 AND 128),
    due_date        date NOT NULL,
    blocking_reason text NOT NULL CHECK (length(blocking_reason) BETWEEN 1 AND 500),
    UNIQUE (tenant_id, statement_ref, ordinal),
    FOREIGN KEY (tenant_id, statement_ref) REFERENCES access.handover_statement (tenant_id, statement_ref)
);

ALTER TABLE access.consent ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.consent FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.consent
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.handover_statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.handover_statement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.handover_statement
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.handover_statement_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.handover_statement_document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.handover_statement_document
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.consent TO access_runtime;
GRANT SELECT, INSERT ON access.handover_statement TO access_runtime;
GRANT SELECT, INSERT ON access.handover_statement_document TO access_runtime;

-- Whether any consent of any tenant is live. VP-74 is an installation setting, and row-level security hides every other
-- tenant's rows from the runtime login, so the switch-off check (INV-REQ-2757, INV-AC-2768) asks this capability
-- function, owned by access_sys, which reads nothing but the existence of a live row (STK-16).
GRANT USAGE ON SCHEMA access TO access_sys;
CREATE POLICY consent_sys_select ON access.consent FOR SELECT TO access_sys USING (true);
GRANT SELECT (status) ON access.consent TO access_sys;
CREATE FUNCTION access.live_consent_exists() RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM access.consent WHERE status = 'LIVE')
$$;
GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.live_consent_exists() OWNER TO access_sys;
REVOKE ALL ON FUNCTION access.live_consent_exists() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.live_consent_exists() TO access_runtime;
REVOKE CREATE ON SCHEMA access FROM access_sys;
