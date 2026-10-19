package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The claim, consumer-offset and triage functions of [OutboxSchema.ddl] (split out of that object). */
internal object OutboxFunctionsSql {
    const val MAX_TRIAGE_LIMIT = 1000

    /**
     * The two claim-read functions (INV-Q-5612, INV-T-3978's pattern): static SQL, `SET search_path = pg_catalog,
     * <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to the runtime login only, owner the sys role. They
     * read [OutboxSchema.OUTBOX_CLAIM_VIEW]'s rows, which the sys role owns.
     */
    fun claimFunctions(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.${OutboxSchema.OUTBOX_CLAIM_PAGE}(after_seq bigint, page_size integer, unpublished_only boolean)
            returns table (seq bigint, tenant_id uuid, published_at timestamptz)
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                return query select v.seq, v.tenant_id, v.published_at from $s.${OutboxSchema.OUTBOX_CLAIM_VIEW} v
                    where v.seq > after_seq and (not unpublished_only or v.published_at is null)
                    order by v.seq limit page_size;
            end
            $$;
            create function $s.${OutboxSchema.OUTBOX_CLAIM_PENDING}() returns bigint
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                return (select count(*) from $s.${OutboxSchema.OUTBOX_CLAIM_VIEW} v where v.published_at is null);
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.OUTBOX_CLAIM_PAGE}(bigint, integer, boolean) owner to $sys;
            revoke all on function $s.${OutboxSchema.OUTBOX_CLAIM_PAGE}(bigint, integer, boolean) from public;
            grant execute on function $s.${OutboxSchema.OUTBOX_CLAIM_PAGE}(bigint, integer, boolean) to $runtime;
            alter function $s.${OutboxSchema.OUTBOX_CLAIM_PENDING}() owner to $sys;
            revoke all on function $s.${OutboxSchema.OUTBOX_CLAIM_PENDING}() from public;
            grant execute on function $s.${OutboxSchema.OUTBOX_CLAIM_PENDING}() to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /**
     * The offset lock, the offset advance and the source-seen record (INV-Q-5873): the platform-scoped tables are
     * written
     * in the record's tenant transaction through these sys-owned definers, so the offset moves atomically with the
     * inbox
     * row. Static SQL, `SET search_path = pg_catalog, <schema>, pg_temp`, EXECUTE revoked from PUBLIC and granted to
     * the
     * runtime login only, owner the sys role.
     */
    fun consumerFunctions(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String = listOf(consumerBodies(schema), consumerGrants(schema, runtimeRole, sysRole)).joinToString("\n")

    private fun consumerBodies(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create function $s.${OutboxSchema.CONSUMER_OFFSET_LOCK}(p_consumer text, p_topic text) returns bigint
            language plpgsql volatile security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_next bigint;
            begin
                insert into $s.${OutboxSchema.CONSUMER_OFFSET} (consumer, topic, next_offset) values (p_consumer, p_topic, 1)
                    on conflict do nothing;
                select o.next_offset into v_next from $s.${OutboxSchema.CONSUMER_OFFSET} o
                    where o.consumer = p_consumer and o.topic = p_topic for update;
                if not found then
                    raise exception 'offset row missing';
                end if;
                return v_next;
            end
            $$;
            create function $s.${OutboxSchema.CONSUMER_OFFSET_ADVANCE}(p_consumer text, p_topic text, p_next_offset bigint) returns void
            language plpgsql volatile security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                update $s.${OutboxSchema.CONSUMER_OFFSET} set next_offset = p_next_offset, attempts = 0, infra_failures = 0,
                    first_infra_failure_at = null, retry_not_before = null
                where consumer = p_consumer and topic = p_topic;
            end
            $$;
            create function $s.${OutboxSchema.CONSUMER_SOURCE_SEEN_RECORD}(p_consumer text, p_producer text, p_source text) returns void
            language plpgsql volatile security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                insert into $s.${OutboxSchema.CONSUMER_SOURCE_SEEN} (consumer, producer, source)
                    values (p_consumer, p_producer, p_source) on conflict do nothing;
            end
            $$;
            """.trimIndent()
    }

    private fun consumerGrants(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.CONSUMER_OFFSET_LOCK}(text, text) owner to $sys;
            revoke all on function $s.${OutboxSchema.CONSUMER_OFFSET_LOCK}(text, text) from public;
            grant execute on function $s.${OutboxSchema.CONSUMER_OFFSET_LOCK}(text, text) to $runtime;
            alter function $s.${OutboxSchema.CONSUMER_OFFSET_ADVANCE}(text, text, bigint) owner to $sys;
            revoke all on function $s.${OutboxSchema.CONSUMER_OFFSET_ADVANCE}(text, text, bigint) from public;
            grant execute on function $s.${OutboxSchema.CONSUMER_OFFSET_ADVANCE}(text, text, bigint) to $runtime;
            alter function $s.${OutboxSchema.CONSUMER_SOURCE_SEEN_RECORD}(text, text, text) owner to $sys;
            revoke all on function $s.${OutboxSchema.CONSUMER_SOURCE_SEEN_RECORD}(text, text, text) from public;
            grant execute on function $s.${OutboxSchema.CONSUMER_SOURCE_SEEN_RECORD}(text, text, text) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /**
     * The dead-letter triage page (INV-Q-5875): the eleven non-content columns of
     * [OutboxSchema.DEAD_LETTER_TRIAGE_VIEW] (the
     * sys-owned
     * view reads every tenant's letters, including the platform tenant's), each filter applied only when its argument
     * is
     * not null, `order by id limit p_limit`; a limit outside 1..[MAX_TRIAGE_LIMIT] is refused. Same shape as
     * [claimFunctions].
     */
    fun triagePage(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val args = "bigint, text, bigint[], uuid, text, text, text, timestamptz, timestamptz, boolean, uuid, integer"
        return """
            create function $s.${OutboxSchema.DEAD_LETTER_TRIAGE_PAGE}(p_after bigint, p_status text, p_ids bigint[], p_tenant uuid,
                p_consumer text, p_topic text, p_failure text, p_from timestamptz, p_before timestamptz,
                p_with_tenant boolean, p_platform uuid, p_limit integer)
            returns table (id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text,
                status text, recorded_at timestamptz, resolved_at timestamptz, note text, replayed_to text)
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                if p_limit is null or p_limit < 1 or p_limit > $MAX_TRIAGE_LIMIT then
                    raise exception 'the triage page limit must be in 1..$MAX_TRIAGE_LIMIT' using errcode = '22023';
                end if;
                return query select v.id, v.consumer, v.topic, v.log_offset, v.tenant_id, v.reason, v.status,
                        v.recorded_at, v.resolved_at, v.note, v.replayed_to
                    from $s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW} v
                    where v.id > p_after
                      and (p_status is null or v.status = p_status)
                      and (p_ids is null or v.id = any(p_ids))
                      and (p_tenant is null or v.tenant_id = p_tenant)
                      and (p_consumer is null or v.consumer = p_consumer)
                      and (p_topic is null or v.topic = p_topic)
                      and (p_failure is null or starts_with(v.reason, p_failure))
                      and (p_from is null or v.recorded_at >= p_from)
                      and (p_before is null or v.recorded_at < p_before)
                      and (not p_with_tenant or v.tenant_id <> p_platform)
                    order by v.id limit p_limit;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.${OutboxSchema.DEAD_LETTER_TRIAGE_PAGE}($args) owner to $sys;
            revoke all on function $s.${OutboxSchema.DEAD_LETTER_TRIAGE_PAGE}($args) from public;
            grant execute on function $s.${OutboxSchema.DEAD_LETTER_TRIAGE_PAGE}($args) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /*
     * The purge functions: each refuses a cut-off later than `now() - <floor>` (the retention floor) and a batch
     * outside
     * 1..[OutboxSchema.MAX_PURGE_BATCH], then deletes with its own predicate, which selects only expired rows. Each
     * function is
     * written out in full and handed to the sys role in the same statement group, so every definer's owner is visible
     * where it is created (INV-ADR-3968 item 6).
     */
}
