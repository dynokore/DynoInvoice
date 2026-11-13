-- V202611140900__create_authority_incarnation.sql (INV-T-9261; INV-Q-9243 B (a); CS-19)
-- Run only by the service's migration Job as access_migrate (never at start-up).
-- The incarnation (epoch) of the authority versions and the high-water mark of the highest authorityVersion published.
-- Versions order within one incarnation; across incarnations the order is by incarnation first. The value rises in two
-- cases only: at start, when authority_version_seq is below the mark (a reset or setval backwards), and on bump(), the
-- store function the restore runbook reaches through the operation of INV-T-22072. It never falls. The table has no
-- tenant column and no row level security: it is platform data, written only through the SECURITY DEFINER functions
-- below, owned by the NOLOGIN access_sys (PostgreSQL "Writing SECURITY DEFINER Functions Safely"). No DELETE is granted.
SET lock_timeout = '5s';

CREATE TABLE access.authority_incarnation (
    singleton            boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    incarnation          bigint  NOT NULL CHECK (incarnation >= 1),
    published_high_water bigint  NOT NULL DEFAULT 0 CHECK (published_high_water >= 0)
);
COMMENT ON TABLE access.authority_incarnation IS 'platform-scoped: no tenant data';

GRANT SELECT ON access.authority_incarnation TO access_runtime;
GRANT SELECT, INSERT, UPDATE ON access.authority_incarnation TO access_sys;
GRANT USAGE, SELECT ON SEQUENCE access.authority_version_seq TO access_sys;

-- The start-time rule: the first value on an empty table is p_now; the value rises to max(stored + 1, p_now) only when
-- the sequence is below the mark. A bump rebases the mark and the head on the sequence, so the next start does not bump
-- again and the heartbeat does not keep reporting a version of the old incarnation.
CREATE FUNCTION access.start_authority_incarnation(p_now bigint) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
DECLARE
    v_seq bigint;
    v_inc bigint;
    v_mark bigint;
BEGIN
    IF p_now IS NULL OR p_now < 1 THEN
        RAISE EXCEPTION 'epoch seconds must be at least 1' USING ERRCODE = '22023';
    END IF;
    INSERT INTO access.authority_incarnation (singleton, incarnation) VALUES (true, p_now) ON CONFLICT (singleton) DO NOTHING;
    SELECT i.incarnation, i.published_high_water INTO v_inc, v_mark
      FROM access.authority_incarnation i WHERE i.singleton FOR UPDATE;
    SELECT CASE WHEN s.is_called THEN s.last_value ELSE 0 END INTO v_seq FROM access.authority_version_seq s;
    IF v_seq < v_mark THEN
        v_inc := GREATEST(v_inc + 1, p_now);
        UPDATE access.authority_incarnation SET incarnation = v_inc, published_high_water = v_seq WHERE singleton;
        IF v_seq >= 1 THEN
            UPDATE access.authority_head SET authority_version = v_seq WHERE singleton;
        END IF;
    END IF;
    RETURN v_inc;
END
$$;

-- The operator bump: max(stored + 1, p_now), so a table restored from an older backup rises above the stored value.
CREATE FUNCTION access.bump_authority_incarnation(p_now bigint) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
DECLARE
    v_inc bigint;
BEGIN
    IF p_now IS NULL OR p_now < 1 THEN
        RAISE EXCEPTION 'epoch seconds must be at least 1' USING ERRCODE = '22023';
    END IF;
    INSERT INTO access.authority_incarnation AS i (singleton, incarnation) VALUES (true, p_now)
    ON CONFLICT (singleton) DO UPDATE SET incarnation = GREATEST(i.incarnation + 1, excluded.incarnation)
    RETURNING i.incarnation INTO v_inc;
    RETURN v_inc;
END
$$;

-- The high-water mark is written by the function every publish already calls (INV-T-9060), in the same statement
-- sequence and transaction. A publish before any start (not a running service) takes the database clock as the first value.
CREATE OR REPLACE FUNCTION access.advance_authority_head(p_version bigint) RETURNS SETOF bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
BEGIN
    IF p_version IS NULL OR p_version < 1 THEN
        RAISE EXCEPTION 'authority version must be at least 1' USING ERRCODE = '22023';
    END IF;
    INSERT INTO access.authority_incarnation AS i (singleton, incarnation, published_high_water)
    VALUES (true, floor(extract(epoch FROM clock_timestamp()))::bigint, p_version)
    ON CONFLICT (singleton) DO UPDATE
        SET published_high_water = GREATEST(i.published_high_water, excluded.published_high_water);
    RETURN QUERY
    INSERT INTO access.authority_head AS h (singleton, authority_version) VALUES (true, p_version)
    ON CONFLICT (singleton) DO UPDATE
        SET authority_version = GREATEST(h.authority_version, excluded.authority_version)
    RETURNING h.authority_version;
END
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.start_authority_incarnation(bigint) OWNER TO access_sys;
ALTER FUNCTION access.bump_authority_incarnation(bigint) OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.start_authority_incarnation(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION access.bump_authority_incarnation(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.start_authority_incarnation(bigint) TO access_runtime;
GRANT EXECUTE ON FUNCTION access.bump_authority_incarnation(bigint) TO access_runtime;
