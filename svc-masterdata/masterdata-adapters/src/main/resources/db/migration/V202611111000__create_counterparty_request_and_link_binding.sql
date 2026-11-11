-- INV-T-18221 (INV-REQ-901, INV-REQ-902; CS-19, ARC-20, TPL-12). The counterparty requests masterdata-svc owns and the
-- binding of an access-svc link to a request, kept from SignedLinkIssued and SignedLinkInvalidated. Run only by the
-- migration Job as masterdata_migrate. A link binding is written by an idempotent, order-insensitive consumer: every
-- column is set from the event that carries it, so a repeated or reordered event leaves the row equal.

CREATE TABLE masterdata.counterparty_request (
    tenant_id         uuid        NOT NULL,
    request_id        text        NOT NULL CHECK (length(request_id) BETWEEN 1 AND 40),
    entity            text        NOT NULL CHECK (length(entity) BETWEEN 1 AND 40),
    party_ref         text        NOT NULL CHECK (length(party_ref) BETWEEN 1 AND 40),
    contact_email     text        NOT NULL CHECK (length(contact_email) BETWEEN 1 AND 254),
    contact_mobile    text        CHECK (length(contact_mobile) BETWEEN 1 AND 20),
    status            text        NOT NULL CHECK (status IN ('SENT', 'ANSWERED')),
    last_requested_at timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, request_id)
);

-- at most one open (SENT) request per counterparty (INV-REQ-901: a repeat returns the existing open request)
CREATE UNIQUE INDEX counterparty_request_open ON masterdata.counterparty_request (tenant_id, party_ref) WHERE status = 'SENT';

ALTER TABLE masterdata.counterparty_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.counterparty_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.counterparty_request
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.counterparty_request TO masterdata_runtime;

-- tenant_id is the tenant the event was consumed under (row-level security); tenant is the tenant text SignedLinkIssued
-- names, null until that event is applied. invalidated_at, request_id and expiry are null until their event is applied.
-- last_event_id is the greatest id of the events applied (UUIDv7 text sorts by time), so it too is order-insensitive.
CREATE TABLE masterdata.link_binding (
    link_ref      text        PRIMARY KEY CHECK (link_ref ~ '^LNK-[A-Za-z0-9_-]{22}$'),
    tenant_id     uuid        NOT NULL,
    tenant        text,
    request_id    text        CHECK (length(request_id) BETWEEN 1 AND 40),
    expiry        timestamptz,
    invalidated_at timestamptz,
    last_event_id text        CHECK (length(last_event_id) BETWEEN 1 AND 100)
);

CREATE INDEX link_binding_request ON masterdata.link_binding (tenant_id, request_id);

ALTER TABLE masterdata.link_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE masterdata.link_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON masterdata.link_binding
    USING      (tenant_id = (select masterdata.bound_tenant()))
    WITH CHECK (tenant_id = (select masterdata.bound_tenant()));

GRANT SELECT, INSERT, UPDATE ON masterdata.link_binding TO masterdata_runtime;
