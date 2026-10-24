-- V202610241000: the filing-frequency history (INV-REQ-705), the frequency-change proposals and their raised review
-- items (INV-REQ-688, INV-REQ-708) and the period records (INV-REQ-83, INV-REQ-84, INV-REQ-707) of a registered
-- entity. Every table is tenant-scoped with forced RLS on the signed binding (INV-REQ-854 b1), insert-only for the
-- runtime role except for the one state column each table lists below (a state moves forward; no value is
-- overwritten and nothing is deleted). Run by tenancy_migrate.
SET lock_timeout = '5s';

-- One row per (entity, effective date) that is not superseded: the frequency code in force from that date, the IRAS
-- approval reference and the state. A PENDING row is a proposal awaiting confirmPeriods; it becomes ACTIVE there.
CREATE TABLE tenancy.filing_frequency_history (
    id                       uuid        PRIMARY KEY,
    tenant_id                uuid        NOT NULL,
    entity_id                uuid        NOT NULL,
    effective_from           date        NOT NULL,
    frequency                text        NOT NULL CHECK (length(frequency) BETWEEN 1 AND 64),
    iras_approval_reference  text        NOT NULL CHECK (length(iras_approval_reference) BETWEEN 1 AND 64),
    state                    text        NOT NULL CHECK (state IN ('ACTIVE', 'PENDING', 'SUPERSEDED')),
    recorded_at              timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id)
);

CREATE UNIQUE INDEX filing_frequency_history_live
    ON tenancy.filing_frequency_history (tenant_id, entity_id, effective_from) WHERE state <> 'SUPERSEDED';
CREATE UNIQUE INDEX filing_frequency_history_one_pending
    ON tenancy.filing_frequency_history (tenant_id, entity_id) WHERE state = 'PENDING';

-- The periods a proposal computed, held apart from the period records until the proposal is applied.
CREATE TABLE tenancy.proposed_period (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    entity_id      uuid        NOT NULL,
    history_id     uuid        NOT NULL,
    period_ref     text        NOT NULL CHECK (length(period_ref) BETWEEN 1 AND 32),
    period_start   date        NOT NULL,
    period_end     date        NOT NULL,
    return_form    text        NOT NULL CHECK (length(return_form) BETWEEN 1 AND 64),
    due_date       date        NOT NULL,
    kinds          text        NOT NULL,
    irregular      boolean     NOT NULL,
    UNIQUE (tenant_id, history_id, period_start),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    FOREIGN KEY (tenant_id, history_id) REFERENCES tenancy.filing_frequency_history (tenant_id, id),
    CHECK (period_end >= period_start)
);

-- One raised review item per code and per proposed change: PER-004 always, PER-005 where a transitional period results.
CREATE TABLE tenancy.raised_item (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    entity_id    uuid        NOT NULL,
    history_id   uuid        NOT NULL,
    code         text        NOT NULL CHECK (code IN ('PER-004', 'PER-005')),
    state        text        NOT NULL CHECK (state IN ('OPEN', 'RESOLVED', 'SUPERSEDED')),
    raised_at    timestamptz NOT NULL,
    UNIQUE (tenant_id, history_id, code),
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    FOREIGN KEY (tenant_id, history_id) REFERENCES tenancy.filing_frequency_history (tenant_id, id)
);

-- The period records: explicit start and end, return form, filing due date, kinds, irregular flag and confirmation
-- state. A superseded record stays stored and is never listed as in force.
CREATE TABLE tenancy.period_record (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    entity_id           uuid        NOT NULL,
    period_ref          text        NOT NULL CHECK (length(period_ref) BETWEEN 1 AND 32),
    period_start        date        NOT NULL,
    period_end          date        NOT NULL,
    return_form         text        NOT NULL CHECK (length(return_form) BETWEEN 1 AND 64),
    due_date            date        NOT NULL,
    kinds               text        NOT NULL,
    irregular           boolean     NOT NULL,
    confirmation_state  text        NOT NULL CHECK (confirmation_state IN ('UNCONFIRMED', 'CONFIRMED')),
    record_state        text        NOT NULL CHECK (record_state IN ('IN_FORCE', 'SUPERSEDED')),
    recorded_at         timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, entity_id) REFERENCES tenancy.entity (tenant_id, id),
    CHECK (period_end >= period_start),
    CHECK (irregular = (kinds <> ''))
);

CREATE UNIQUE INDEX period_record_in_force_ref
    ON tenancy.period_record (tenant_id, entity_id, period_ref) WHERE record_state = 'IN_FORCE';
CREATE UNIQUE INDEX period_record_in_force_start
    ON tenancy.period_record (tenant_id, entity_id, period_start) WHERE record_state = 'IN_FORCE';

ALTER TABLE tenancy.filing_frequency_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.filing_frequency_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.filing_frequency_history
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.proposed_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.proposed_period FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.proposed_period
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.raised_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.raised_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.raised_item
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

ALTER TABLE tenancy.period_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.period_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy.period_record
    USING      (tenant_id = (select tenancy.bound_tenant()))
    WITH CHECK (tenant_id = (select tenancy.bound_tenant()));

GRANT SELECT, INSERT ON tenancy.filing_frequency_history, tenancy.proposed_period, tenancy.raised_item,
    tenancy.period_record TO tenancy_runtime;
GRANT UPDATE (state) ON tenancy.filing_frequency_history TO tenancy_runtime;
GRANT UPDATE (state) ON tenancy.raised_item TO tenancy_runtime;
GRANT UPDATE (confirmation_state, record_state) ON tenancy.period_record TO tenancy_runtime;
