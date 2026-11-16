-- V202611161000__create_access_review.sql (INV-T-15798; INV-REQ-894, INV-REQ-1266; CS-19, ARC-20, TPL-12)
-- Run only by the service's migration Job as access_migrate (never at start-up). The runtime login owns nothing and
-- never deletes: a campaign is closed by the outcomes recorded, not by a status written to it.

-- One campaign per (tenant, period): a repeated runAccessReview answers the existing row (unique key below).
CREATE TABLE access.review_campaign (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    campaign_ref  text        NOT NULL CHECK (length(campaign_ref) BETWEEN 1 AND 64),
    period        text        NOT NULL CHECK (period ~ '^[0-9]{4}Q[1-4]$' OR period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    cadence       text        NOT NULL CHECK (cadence IN ('QUARTERLY', 'MONTHLY')),
    opened_by     text        NOT NULL CHECK (length(opened_by) BETWEEN 1 AND 64),
    opened_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, campaign_ref),
    UNIQUE (tenant_id, period),
    CHECK ((cadence = 'QUARTERLY') = (period ~ '^[0-9]{4}Q[1-4]$'))
);

-- The grants a campaign holds at opening and the person who holds each one then.
CREATE TABLE access.review_campaign_grant (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    campaign_ref  text        NOT NULL,
    grant_ref     text        NOT NULL CHECK (length(grant_ref) BETWEEN 1 AND 64),
    holder_ref    text        NOT NULL CHECK (length(holder_ref) BETWEEN 1 AND 64),
    UNIQUE (tenant_id, campaign_ref, grant_ref),
    FOREIGN KEY (tenant_id, campaign_ref) REFERENCES access.review_campaign (tenant_id, campaign_ref)
);

-- The outcome of one grant in one campaign: by whom (a user ID, never a role) and when.
CREATE TABLE access.review_outcome (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    campaign_ref  text        NOT NULL,
    grant_ref     text        NOT NULL,
    decision      text        NOT NULL CHECK (decision IN ('KEEP', 'REVOKE')),
    actor_ref     text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 64),
    decided_at    timestamptz NOT NULL,
    ack_ref       text        CHECK (ack_ref IS NULL OR length(ack_ref) BETWEEN 1 AND 64),
    UNIQUE (tenant_id, campaign_ref, grant_ref),
    FOREIGN KEY (tenant_id, campaign_ref, grant_ref)
        REFERENCES access.review_campaign_grant (tenant_id, campaign_ref, grant_ref)
);

ALTER TABLE access.review_campaign ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.review_campaign FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.review_campaign
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.review_campaign_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.review_campaign_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.review_campaign_grant
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

ALTER TABLE access.review_outcome ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.review_outcome FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access.review_outcome
    USING      (tenant_id = (select access.bound_tenant()))
    WITH CHECK (tenant_id = (select access.bound_tenant()));

-- select for update on a campaign needs UPDATE
GRANT SELECT, INSERT, UPDATE ON access.review_campaign TO access_runtime;
GRANT SELECT, INSERT ON access.review_campaign_grant TO access_runtime;
GRANT SELECT, INSERT ON access.review_outcome TO access_runtime;
