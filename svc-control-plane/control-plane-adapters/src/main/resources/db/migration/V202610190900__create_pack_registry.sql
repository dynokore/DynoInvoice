-- V202610190900__create_pack_registry.sql (TPL-12, CS-19, ARC-20, INV-REQ-854 item 2, INV-ADR-3968 item 4a)
-- Run only by the migration Job through service-runtime's MigrationRunner as controlplane_migrate, which owns the
-- schema and every table; never at service start-up. The regulatory pack registry is PLATFORM data: it belongs to the
-- operator, not to a tenant, so these tables have no tenant column and each is declared platform-scoped by its table
-- comment (the migration lint and TableClasses.verify read that comment). The runtime login owns nothing and holds no
-- DELETE anywhere: a published pack version is superseded, never unpublished (INV-REQ-2590, INV-AC-2594).

CREATE TABLE controlplane.pack_version (
    pack_ref           text        PRIMARY KEY CHECK (length(pack_ref) BETWEEN 1 AND 64),
    pack_type          text        NOT NULL CHECK (length(pack_type) BETWEEN 1 AND 32),
    pack_version       text        NOT NULL CHECK (length(pack_version) BETWEEN 1 AND 16),
    status             text        NOT NULL CHECK (status IN ('DRAFT', 'SIGNED', 'PUBLISHED')),
    citations          text[]      NOT NULL CHECK (cardinality(citations) <= 50),
    min_engine_version text        CHECK (length(min_engine_version) BETWEEN 1 AND 32),
    exposure_reducing  boolean     NOT NULL DEFAULT false,
    signer             text        CHECK (length(signer) BETWEEN 1 AND 128),
    signing_key_ref    text        CHECK (length(signing_key_ref) BETWEEN 1 AND 64),
    created_at         timestamptz NOT NULL,
    signed_at          timestamptz,
    published_at       timestamptz,
    UNIQUE (pack_type, pack_version),
    -- a version has a signer exactly when it is signed, and a publication time exactly when it is published
    CHECK ((status = 'DRAFT') = (signer IS NULL AND signing_key_ref IS NULL AND signed_at IS NULL)),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
COMMENT ON TABLE controlplane.pack_version IS 'platform-scoped: no tenant data';

-- Every identity that created or edited an entry of the version through authorRegulatoryPack (INV-REQ-2590 b2). The
-- signer is never one of them: the pair of tables is checked by the application and re-checked by the trigger below.
CREATE TABLE controlplane.pack_author (
    pack_ref    text        NOT NULL REFERENCES controlplane.pack_version (pack_ref),
    actor       text        NOT NULL CHECK (length(actor) BETWEEN 1 AND 128),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (pack_ref, actor)
);
COMMENT ON TABLE controlplane.pack_author IS 'platform-scoped: no tenant data';

-- One immutable record per signature: who signed which version with which key reference. The key itself is never
-- stored: only its reference, read through the SecretStore binding (STK-29, CS-24).
CREATE TABLE controlplane.signing_record (
    pack_ref        text        PRIMARY KEY REFERENCES controlplane.pack_version (pack_ref),
    signer          text        NOT NULL CHECK (length(signer) BETWEEN 1 AND 128),
    signing_key_ref text        NOT NULL CHECK (length(signing_key_ref) BETWEEN 1 AND 64),
    signed_at       timestamptz NOT NULL
);
COMMENT ON TABLE controlplane.signing_record IS 'platform-scoped: no tenant data';

-- Separation of duties, non-waivable (INV-REQ-2590): a signature by an author of the version is refused by the
-- database too, whatever the application did. Static SQL, fixed search_path, no EXECUTE for PUBLIC.
CREATE FUNCTION controlplane.signing_record_separation() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, controlplane, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM controlplane.pack_author a WHERE a.pack_ref = NEW.pack_ref AND a.actor = NEW.signer) THEN
        RAISE EXCEPTION 'the signer edited the pack version (separation of duties)' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;
REVOKE EXECUTE ON FUNCTION controlplane.signing_record_separation() FROM PUBLIC;
CREATE TRIGGER signing_record_separation BEFORE INSERT ON controlplane.signing_record
    FOR EACH ROW EXECUTE FUNCTION controlplane.signing_record_separation();

-- The runtime login reads and writes the registry through the application only. Append-only where a record is final.
GRANT USAGE ON SCHEMA controlplane TO controlplane_runtime;
GRANT SELECT, INSERT, UPDATE ON controlplane.pack_version TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.pack_author TO controlplane_runtime;
GRANT SELECT, INSERT ON controlplane.signing_record TO controlplane_runtime;
