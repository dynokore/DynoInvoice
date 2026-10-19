-- V202610200900__create_pack_release.sql (TPL-12, CS-19, INV-REQ-1077, INV-REQ-1079, INV-AC-2074, INV-AC-2594)
-- The release of a published pack version: the signed bundle (the four members of rulepack-kernel PackArtefact), the
-- release history and the feed index documents. All three tables are PLATFORM data (no tenant column), each declared
-- platform-scoped by its table comment. The runtime login reads and appends only: a release is never changed or removed,
-- a published pack is superseded, never unpublished (INV-REQ-2590, INV-AC-2594).

CREATE TABLE controlplane.pack_release (
    pack_ref          text        PRIMARY KEY REFERENCES controlplane.pack_version (pack_ref),
    -- SHA-256 over the manifest, checksums and contents members, the value of the feed index packContentHash
    pack_content_hash text        NOT NULL CHECK (pack_content_hash ~ '^[0-9a-f]{64}$'),
    supersedes        text        REFERENCES controlplane.pack_version (pack_ref),
    manifest          bytea       NOT NULL,
    checksums         bytea       NOT NULL,
    signature         bytea       NOT NULL,
    contents          bytea       NOT NULL,
    released_at       timestamptz NOT NULL,
    CHECK (supersedes IS NULL OR supersedes <> pack_ref)
);
COMMENT ON TABLE controlplane.pack_release IS 'platform-scoped: no tenant data';

-- One row per index document ever served; the highest generation is the one served. The document is JSON text that
-- passed pack-feed-index.schema.json of platform-contracts before this row was written.
CREATE TABLE controlplane.pack_feed_index (
    generation  integer     PRIMARY KEY CHECK (generation >= 1),
    document    text        NOT NULL,
    recorded_at timestamptz NOT NULL
);
COMMENT ON TABLE controlplane.pack_feed_index IS 'platform-scoped: no tenant data';

GRANT SELECT, INSERT ON controlplane.pack_release TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.pack_feed_index TO controlplane_runtime;

-- A published version is final, whatever the application did: it is superseded by a later version, never changed or
-- unpublished (INV-REQ-2590, INV-AC-2594). Static SQL, fixed search_path, no EXECUTE for PUBLIC.
CREATE FUNCTION controlplane.pack_version_published_final() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, controlplane, pg_temp AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'a published pack version is final' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
REVOKE EXECUTE ON FUNCTION controlplane.pack_version_published_final() FROM PUBLIC;
CREATE TRIGGER pack_version_published_final BEFORE UPDATE ON controlplane.pack_version
    FOR EACH ROW EXECUTE FUNCTION controlplane.pack_version_published_final();
