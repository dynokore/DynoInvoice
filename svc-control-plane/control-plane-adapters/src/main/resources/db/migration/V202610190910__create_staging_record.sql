-- V202610190910__create_staging_record.sql (INV-T-4984; coordinator ruling INV-Q-4878; the pattern of the sys-owned
-- functions of INV-T-4137)
-- The staging evidence record the publication gate reads. The table is PLATFORM data (no tenant column). The runtime
-- login holds SELECT only: no INSERT, UPDATE or DELETE. Rows are written only through record_staging_result, a
-- SECURITY DEFINER function owned by the NOLOGIN controlplane_sys, which the one class StagingRecordWriter calls and
-- only the stage of INV-T-4985 may use.

CREATE TABLE controlplane.pack_staging_record (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    pack_ref           text        NOT NULL REFERENCES controlplane.pack_version (pack_ref),
    result             text        NOT NULL CHECK (result IN ('PASSED', 'FAILED', 'PENDING')),
    pack_content_hash  text        NOT NULL CHECK (pack_content_hash ~ '^[0-9a-f]{64}$'),
    target             text        CHECK (target IN ('IRAS_SANDBOX', 'SIMULATOR')),
    evidence_ref       text        CHECK (length(evidence_ref) BETWEEN 1 AND 256),
    replayed_period_ref text       CHECK (length(replayed_period_ref) BETWEEN 1 AND 32),
    recorded_by        text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 128),
    recorded_at        timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX pack_staging_record_pack ON controlplane.pack_staging_record (pack_ref, recorded_at);
COMMENT ON TABLE controlplane.pack_staging_record IS 'platform-scoped: no tenant data';

GRANT SELECT ON controlplane.pack_staging_record TO controlplane_runtime;
GRANT USAGE ON SCHEMA controlplane TO controlplane_sys;
GRANT SELECT, INSERT ON controlplane.pack_staging_record TO controlplane_sys;
GRANT SELECT ON controlplane.pack_version TO controlplane_sys;

CREATE FUNCTION controlplane.record_staging_result(
    p_pack_ref text, p_result text, p_pack_content_hash text, p_target text, p_evidence_ref text,
    p_replayed_period_ref text, p_recorded_by text
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, controlplane, pg_temp AS $$
DECLARE
    v_id uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM controlplane.pack_version v WHERE v.pack_ref = p_pack_ref) THEN
        RAISE EXCEPTION 'no pack version %', p_pack_ref USING ERRCODE = '23503';
    END IF;
    INSERT INTO controlplane.pack_staging_record
        (pack_ref, result, pack_content_hash, target, evidence_ref, replayed_period_ref, recorded_by)
    VALUES (p_pack_ref, p_result, p_pack_content_hash, p_target, p_evidence_ref, p_replayed_period_ref, p_recorded_by)
    RETURNING id INTO v_id;
    RETURN v_id;
END
$$;
-- handing the function over needs CREATE on the schema for the new owner, for this statement only
GRANT CREATE ON SCHEMA controlplane TO controlplane_sys;
ALTER FUNCTION controlplane.record_staging_result(text, text, text, text, text, text, text) OWNER TO controlplane_sys;
REVOKE CREATE ON SCHEMA controlplane FROM controlplane_sys;
REVOKE EXECUTE ON FUNCTION controlplane.record_staging_result(text, text, text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION controlplane.record_staging_result(text, text, text, text, text, text, text) TO controlplane_runtime;
