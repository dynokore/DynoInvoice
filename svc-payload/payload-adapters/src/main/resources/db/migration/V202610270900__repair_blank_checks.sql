SET lock_timeout = '5s';

-- Restores U+FEFF (written as \uFEFF) in the blank checks Flyway built: Flyway 12 drops raw U+FEFF from a script (INV-T-10836).

create or replace function payload.full_sync_delete(p_consumer text, p_source text) returns bigint
language plpgsql security definer set search_path = pg_catalog, payload, pg_temp as $$
declare
    deleted bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_source is null or p_source ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$' then
        raise exception 'a full sync names one consumer and one source' using errcode = '22023';
    end if;
    delete from payload.consumer_sequence where consumer = p_consumer and source = p_source;
    get diagnostics deleted = row_count;
    return deleted;
end
$$;
grant create on schema payload to payload_sys;
alter function payload.full_sync_delete(text, text) owner to payload_sys;
revoke all on function payload.full_sync_delete(text, text) from public;
grant execute on function payload.full_sync_delete(text, text) to payload_runtime;
revoke create on schema payload from payload_sys;
create or replace function payload.event_dead_letter_record(p_consumer text, p_topic text, p_log_offset bigint, p_tenant_id uuid,
    p_reason text, p_raw text) returns bigint
language plpgsql security definer set search_path = pg_catalog, payload, pg_temp as $$
declare
    recorded bigint;
begin
    if p_consumer is null or p_consumer ~ '^[\u0001-\u001F\u007F-\u009F \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*$'
        or p_topic is null or p_log_offset is null
        or p_reason is null or p_raw is null then
        raise exception 'a dead letter names its consumer, topic, offset, reason and record'
            using errcode = '22023';
    end if;
    insert into payload.event_dead_letter (consumer, topic, log_offset, tenant_id, reason, raw)
    values (p_consumer, p_topic, p_log_offset, coalesce(p_tenant_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid), p_reason, p_raw)
    returning id into recorded;
    return recorded;
end
$$;
grant create on schema payload to payload_sys;
alter function payload.event_dead_letter_record(text, text, bigint, uuid, text, text) owner to payload_sys;
revoke all on function payload.event_dead_letter_record(text, text, bigint, uuid, text, text) from public;
grant execute on function payload.event_dead_letter_record(text, text, bigint, uuid, text, text) to payload_runtime;
revoke create on schema payload from payload_sys;
