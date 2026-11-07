SET lock_timeout = '5s';

-- V202611061000__create_signed_link.sql (INV-T-4940; CS-19, ARC 4.3)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- A signed, expiring link (INV-REQ-899, INV-REQ-2762). A link issued by the call carries the consent and client entity
-- it is scoped to; a link issued on SignedLinkRequested carries neither and files its alert under the envelope entity of the event (DR-11). The one-time passcode is never stored
-- in clear: only a digest of it, the wrong-entry count and the state of the link are kept. The verified session is the
-- verified_at instant, read from this record; no token is issued.
CREATE TABLE access.signed_link (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    link_ref          text        NOT NULL CHECK (length(link_ref) BETWEEN 1 AND 64),
    purpose           text        NOT NULL CHECK (length(purpose) BETWEEN 1 AND 64),
    client_ref        text        CHECK (client_ref IS NULL OR length(client_ref) BETWEEN 1 AND 64),
    consent_ref       text        CHECK (consent_ref IS NULL OR length(consent_ref) BETWEEN 1 AND 64),
    alert_entity      text        NOT NULL CHECK (length(alert_entity) BETWEEN 1 AND 64),  -- ACC-004 is filed under it
    subject_reference text        CHECK (subject_reference IS NULL OR length(subject_reference) BETWEEN 1 AND 256),
    recipient_email   text        NOT NULL CHECK (length(recipient_email) BETWEEN 3 AND 254),
    recipient_mobile  text        CHECK (recipient_mobile IS NULL OR length(recipient_mobile) BETWEEN 1 AND 32),
    expires_at        timestamptz NOT NULL,
    passcode_digest   text        NOT NULL CHECK (passcode_digest ~ '^[0-9a-f]{64}$'),
    status            text        NOT NULL CHECK (status IN ('ACTIVE', 'INVALIDATED', 'REVOKED')),
    wrong_entries     integer     NOT NULL CHECK (wrong_entries BETWEEN 0 AND 5),
    verified_at       timestamptz,
    invalidated_at    timestamptz,
    source            text        NOT NULL CHECK (source IN ('CALL', 'EVENT')),
    source_event_id   text        CHECK (source_event_id IS NULL OR length(source_event_id) BETWEEN 1 AND 128),
    created_at        timestamptz NOT NULL,
    created_by        text        NOT NULL CHECK (length(created_by) BETWEEN 1 AND 255),  -- the acting user ID or the producer, never a role
    updated_at        timestamptz NOT NULL,
    updated_by        text        NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 255),
    version           bigint      NOT NULL CHECK (version >= 0),
    UNIQUE (tenant_id, link_ref),
    -- a link scoped to a consent names the client entity of that consent
    CONSTRAINT signed_link_consent_has_client CHECK (consent_ref IS NULL OR client_ref IS NOT NULL),
    -- a link with a client entity files its alert under that entity
    CONSTRAINT signed_link_alert_under_client CHECK (client_ref IS NULL OR client_ref = alert_entity),
    -- the redelivery key of an event-issued link, and only of one
    CONSTRAINT signed_link_event_source CHECK ((source = 'EVENT') = (source_event_id IS NOT NULL)),
    CONSTRAINT signed_link_invalidated_at CHECK ((status = 'INVALIDATED') = (invalidated_at IS NOT NULL))
);

-- One link per delivered event (the topic is at-least-once): the consumer is idempotent on the CloudEvents id.
CREATE UNIQUE INDEX signed_link_source_event_key ON access.signed_link (tenant_id, source_event_id)
    WHERE source_event_id IS NOT NULL;

ALTER TABLE access.signed_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.signed_link FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.signed_link
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON access.signed_link TO access_runtime;

-- A signed-link request arrives with the link reference and no tenant, so the tenant is found from the reference
-- alone, as resolve_invitation_tenant finds the tenant of an invitation: a SECURITY DEFINER function owned by the
-- NOLOGIN access_sys, static SQL, executable only by access_runtime. The one policy applies to access_sys only and
-- access_sys sees the two columns it needs. The reference must name one link, so it is unique on its own.
CREATE UNIQUE INDEX signed_link_ref_key ON access.signed_link (link_ref);

GRANT SELECT (link_ref, tenant_id) ON access.signed_link TO access_sys;
CREATE POLICY signed_link_sys_resolve ON access.signed_link FOR SELECT TO access_sys
    USING (true);

CREATE FUNCTION access.resolve_signed_link_tenant(link_ref text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
    SELECT l.tenant_id FROM access.signed_link l
    WHERE l.link_ref = resolve_signed_link_tenant.link_ref
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.resolve_signed_link_tenant(text) OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.resolve_signed_link_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.resolve_signed_link_tenant(text) TO access_runtime;
