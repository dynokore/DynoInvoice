-- create accreditation_record (TPL-12, CS-19, INV-T-7322, INV-REQ-1363, INV-REQ-1364)
-- The product's IMDA accreditation records. PLATFORM data (no tenant column), declared platform-scoped by the table
-- comment. Append-only: the runtime login reads and inserts; a later record supersedes an earlier one in the status and
-- the earlier one stays readable. recorded_by holds the user ID of the caller, never a role name (INV-Q-10647).

CREATE TABLE controlplane.accreditation_record (
    id                       uuid        PRIMARY KEY,
    granted_on               date        NOT NULL,
    current_until            date        NOT NULL,
    imda_reference           text        NOT NULL CHECK (length(imda_reference) BETWEEN 1 AND 128),
    covered_submission_types text[]      NOT NULL CHECK (cardinality(covered_submission_types) BETWEEN 1 AND 4),
    sandbox_acknowledgements jsonb       NOT NULL,
    recorded_at              timestamptz NOT NULL,
    recorded_by              text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 64),
    CHECK (current_until >= granted_on),
    UNIQUE (imda_reference, current_until)
);
COMMENT ON TABLE controlplane.accreditation_record IS 'platform-scoped: no tenant data';

GRANT SELECT, INSERT ON controlplane.accreditation_record TO controlplane_runtime;
