-- V202611051000: the tenant's own TENANT node of OrganisationStructureChanged is published through a definer function
-- (INV-Q-16720 A, INV-AC-16723; INV-REQ-854 behavior 2, INV-REQ-1125). Run by tenancy_migrate. The runtime login cannot
-- insert an outbox row for a tenant other than the bound one, and the node of a tenant is written under the platform
-- binding (a new production tenant) or the production tenant's binding (a new workspace), so the row is inserted here,
-- after the function has checked it against the stored tenant row. outbox_tenant_isolation is not touched, and no
-- policy targets tenancy_runtime.
SET lock_timeout = '5s';

-- The sys role may insert only this one event type on this one topic (the function's owner; GRANT INSERT is V202610190930).
CREATE POLICY outbox_sys_insert_tenant_node ON tenancy.outbox FOR INSERT TO tenancy_sys
    WITH CHECK (type = 'com.dynokore.gst.tenancy.OrganisationStructureChanged.v1'
                AND topic = 'gst.tenancy.organisation-structure-changed.v1');

-- p_row is the row OutboxWriter.stage built, as the JdbcOutboxWriter insert writes it: 13 members; doc_uuid,
-- transaction_date and docsequence absent or null. 42501 refuses the binding, 22023 the row; nothing is written then.
CREATE FUNCTION tenancy.publish_tenant_node(p_row jsonb) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, tenancy, pg_temp AS $$
DECLARE
    platform constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    bound    uuid := tenancy.bound_tenant();
    t        tenancy.tenant%ROWTYPE;
    d        jsonb;
    t_id     uuid;
    allowed  constant text[] := ARRAY['id', 'topic', 'type', 'source', 'subject', 'event_time', 'tenant_id',
                                      'entity_ref', 'instance_id', 'correlation_id', 'causation_id', 'data', 'testmode'];
    nullable constant text[] := ARRAY['doc_uuid', 'transaction_date', 'docsequence'];
    k        text;
    want     text;
BEGIN
    IF p_row IS NULL OR jsonb_typeof(p_row) <> 'object' THEN
        RAISE EXCEPTION 'the row is not an object' USING ERRCODE = '22023';
    END IF;
    FOR k IN SELECT jsonb_object_keys(p_row) LOOP
        IF NOT (k = ANY (allowed)) THEN
            IF NOT (k = ANY (nullable)) OR jsonb_typeof(p_row -> k) <> 'null' THEN
                RAISE EXCEPTION 'the row has a member that is not an outbox member: %', k USING ERRCODE = '22023';
            END IF;
        END IF;
    END LOOP;
    FOREACH k IN ARRAY allowed LOOP
        IF NOT jsonb_exists(p_row, k) OR jsonb_typeof(p_row -> k) = 'null' THEN
            RAISE EXCEPTION 'the row lacks the member %', k USING ERRCODE = '22023';
        END IF;
        want := CASE k WHEN 'data' THEN 'object' WHEN 'testmode' THEN 'boolean' ELSE 'string' END;
        IF jsonb_typeof(p_row -> k) <> want THEN
            RAISE EXCEPTION 'the member % has the wrong JSON type', k USING ERRCODE = '22023';
        END IF;
    END LOOP;

    IF p_row ->> 'type' <> 'com.dynokore.gst.tenancy.OrganisationStructureChanged.v1'
       OR p_row ->> 'topic' <> 'gst.tenancy.organisation-structure-changed.v1' THEN
        RAISE EXCEPTION 'the row is not a tenancy OrganisationStructureChanged row' USING ERRCODE = '22023';
    END IF;
    IF p_row ->> 'id' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'the id is not a version-7 UUID' USING ERRCODE = '22023';
    END IF;
    IF p_row ->> 'source' !~ '^/gst/[a-z][a-z-]*$' THEN
        RAISE EXCEPTION 'the source is not /gst/<service>' USING ERRCODE = '22023';
    END IF;
    IF btrim(p_row ->> 'instance_id') = '' OR btrim(p_row ->> 'correlation_id') = ''
       OR btrim(p_row ->> 'causation_id') = '' THEN
        RAISE EXCEPTION 'a correlation member is blank' USING ERRCODE = '22023';
    END IF;
    IF p_row ->> 'tenant_id' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'the tenant id is not a UUID' USING ERRCODE = '22023';
    END IF;
    t_id := (p_row ->> 'tenant_id')::uuid;
    SELECT * INTO t FROM tenancy.tenant x WHERE x.tenant_id = t_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'the row names no tenant' USING ERRCODE = '22023';
    END IF;

    -- coalesce: a production tenant has no pairing, and a null comparison must refuse, not pass.
    IF NOT coalesce(bound = t.tenant_id
                    OR bound = t.paired_production_tenant_id
                    OR (bound = platform AND t.environment = 'PRODUCTION' AND t.paired_production_tenant_id IS NULL),
                    false) THEN
        RAISE EXCEPTION 'the binding may not publish this tenant node' USING ERRCODE = '42501';
    END IF;

    d := p_row -> 'data';
    IF p_row ->> 'entity_ref' <> t.ref OR p_row ->> 'subject' <> t.ref
       OR jsonb_typeof(d -> 'nodeRef') IS DISTINCT FROM 'string' OR d ->> 'nodeRef' <> t.ref
       OR d ->> 'nodeType' IS DISTINCT FROM 'TENANT'
       OR d ->> 'changeType' IS DISTINCT FROM (CASE WHEN t.retired THEN 'RETIRED' ELSE 'CREATED' END)
       OR jsonb_exists(d, 'parentRef') OR jsonb_exists(d, 'previousParentRef')
       OR jsonb_typeof(d -> 'node') IS DISTINCT FROM 'object'
       OR jsonb_typeof(d #> '{node,retired}') IS DISTINCT FROM 'boolean'
       OR (d #>> '{node,retired}')::boolean <> t.retired
       OR jsonb_typeof(d #> '{node,structureVersion}') IS DISTINCT FROM 'number'
       OR (d #>> '{node,structureVersion}') !~ '^[0-9]+$'
       OR (d #>> '{node,structureVersion}')::bigint <> t.structure_version
       OR jsonb_typeof(d #> '{node,environment}') IS DISTINCT FROM 'string'
       OR d #>> '{node,environment}' <> t.environment THEN
        RAISE EXCEPTION 'the row does not equal the stored tenant row' USING ERRCODE = '22023';
    END IF;

    INSERT INTO tenancy.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, instance_id,
                                correlation_id, causation_id, data, testmode)
    VALUES (p_row ->> 'id', p_row ->> 'topic', p_row ->> 'type', p_row ->> 'source', p_row ->> 'subject',
            (p_row ->> 'event_time')::timestamptz, t.tenant_id, p_row ->> 'entity_ref', p_row ->> 'instance_id',
            p_row ->> 'correlation_id', p_row ->> 'causation_id', d, (p_row ->> 'testmode')::boolean);
END;
$$;

ALTER FUNCTION tenancy.publish_tenant_node(jsonb) OWNER TO tenancy_sys;
REVOKE ALL ON FUNCTION tenancy.publish_tenant_node(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.publish_tenant_node(jsonb) TO tenancy_runtime;
