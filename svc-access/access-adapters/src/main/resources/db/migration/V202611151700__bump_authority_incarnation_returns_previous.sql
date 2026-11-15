-- V202611151700 (INV-T-22072; INV-Q-9243 B (a), ruling (3)): bump_authority_incarnation returns the value it replaced and
-- the value it stored, read and written in one statement over the locked row, so no check-then-act gap remains
-- (CWE-367). A missing row raises an error (the start-time step has not run). Formula unchanged: max(previous + 1, now).
-- Run only by the service's migration Job as access_migrate (never at start-up). No other function or table is touched.
SET lock_timeout = '5s';

DROP FUNCTION access.bump_authority_incarnation(bigint);

-- One bigint[] {previous, stored}, not RETURNS TABLE or OUT parameters: jOOQ's schema metadata lists a function that
-- returns a record or a set as a table, and the tenancy table check (TableClasses.verify) would refuse it for having
-- no tenant column.
CREATE FUNCTION access.bump_authority_incarnation(p_now bigint) RETURNS bigint[]
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, access, pg_temp AS $$
DECLARE
    v_prev bigint;
    v_new bigint;
BEGIN
    IF p_now IS NULL OR p_now < 1 THEN
        RAISE EXCEPTION 'epoch seconds must be at least 1' USING ERRCODE = '22023';
    END IF;
    SELECT i.incarnation INTO v_prev FROM access.authority_incarnation i WHERE i.singleton FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access.authority_incarnation holds no row: the start-time step has not run' USING ERRCODE = '55000';
    END IF;
    v_new := GREATEST(v_prev + 1, p_now);
    UPDATE access.authority_incarnation i SET incarnation = v_new WHERE i.singleton;
    RETURN ARRAY[v_prev, v_new];
END
$$;

GRANT CREATE ON SCHEMA access TO access_sys;
ALTER FUNCTION access.bump_authority_incarnation(bigint) OWNER TO access_sys;
REVOKE CREATE ON SCHEMA access FROM access_sys;
REVOKE ALL ON FUNCTION access.bump_authority_incarnation(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION access.bump_authority_incarnation(bigint) TO access_runtime;
