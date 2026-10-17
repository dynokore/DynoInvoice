-- Test only (INV-T-4616). Run as container init SQL under SET ROLE to rulepack_kernel_migrate, which owns the table. Inserts one tenant row with the
-- BEFORE INSERT trigger disabled, so that only the registered_by CHECK can refuse the row. Called by the owner inside a
-- transaction that the refusal rolls back, the trigger's disabling with it; a row that passes re-enables the trigger.
-- SECURITY INVOKER, EXECUTE revoked from PUBLIC: no other role can call it.
CREATE FUNCTION rulepack_test.insert_bypassing_the_actor_trigger(p_pack_id text, p_version text, p_tenant uuid,
    p_registered_by text) RETURNS text
LANGUAGE plpgsql SET search_path = pg_catalog, rulepack_test, pg_temp AS $$
BEGIN
    ALTER TABLE rulepack_test.rule_pack_version DISABLE TRIGGER rule_pack_version_registered_by_insert;
    INSERT INTO rulepack_test.rule_pack_version (id, pack_id, pack_type, version, tenant_id, effective_from,
        min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by)
    VALUES (gen_random_uuid(), p_pack_id, 'Scope', p_version, p_tenant, current_date, '1.0.0', '{}'::jsonb,
        '{}'::jsonb, '\x01'::bytea, '{}'::jsonb, 'test-anchor', p_registered_by);
    ALTER TABLE rulepack_test.rule_pack_version ENABLE TRIGGER rule_pack_version_registered_by_insert;
    RETURN p_pack_id;
END
$$;
REVOKE EXECUTE ON FUNCTION rulepack_test.insert_bypassing_the_actor_trigger(text, text, uuid, text) FROM PUBLIC;
