package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.jooq.Name
import org.jooq.impl.DSL
import java.sql.Connection

/**
 * DDL and naming for the `eventlog` schema owned by the event binding (INV-REQ-1205, INV-ADR-283), on the shared-pool
 * role model (owner ruling INV-Q-3953, INV-ADR-3968): each service's single runtime login and the NOLOGIN
 * `eventlog_sys`. The event-log component's migration, run as `eventlog_migrate` (a member of `eventlog_sys`), runs
 * [schemaDdl] once, [topicDdl] once per topic of `event-log/topics.yaml`, then [functionsDdl] over every topic, again
 * whenever the topic set changes, and [writeRetention] at every install and upgrade.
 *
 * Rights (INV-REQ-1205 b3):
 * - A topic table holds every tenant's events, so it is tenant data (INV-REQ-854, CS-19): `tenant_id uuid NOT NULL`
 *   (a platform event is stored under `TenantId.PLATFORM`, INV-Q-2927) with tenancy-kernel's forced row-level security
 *   ([TenantRlsMigration.ddl]). A producer's runtime login holds INSERT on its own topics, the tenant policy's WITH CHECK
 *   binding each row to the transaction's tenant; a subscriber's runtime login holds SELECT under the tenant policy and
 *   SELECT on its topics' purge-watermark rows. No runtime login reads across tenants.
 * - Every cross-tenant operation runs through the SECURITY DEFINER functions [CLAIM], [PURGE_BATCH],
 *   [RECORD_POSITION_FUNCTION] and [EVENTS_MISSING] (owner ruling INV-Q-4350 (A)), owned by `eventlog_sys`, with static SQL (one branch per topic, generated here; no
 *   `EXECUTE`), `SET search_path = pg_catalog, eventlog, pg_temp`, EXECUTE revoked from PUBLIC and granted only to the
 *   runtime logins that need each. `eventlog_sys` alone holds the cross-tenant policies and column grants they use.
 *
 * Rows of the [SUBSCRIPTION], [PRODUCER], [PURGE_WATERMARK] and [RECORD_POSITION] tables are keyed by one mapping, the topic's
 * [tableName] (the `topic_key` column), checked injective over the topic set at generation; the functions map a topic
 * argument to its key the same way (`translate(topic, '.-', '__')`). These tables and [RETENTION] hold no tenant data
 * (`platform-scoped: no tenant data`, INV-ADR-3968 item 4a) and are written only by the migration or the functions.
 */
object EventLogSchema {
    const val SCHEMA = "eventlog"

    /** One row per purged topic: the highest purged `log_offset` (INV-REQ-1205 b6, INV-Q-3249). */
    const val PURGE_WATERMARK = "purge_watermark"

    /** One row per (topic, subscribing runtime login), generated from topics.yaml; read by [CLAIM] only. */
    const val SUBSCRIPTION = "subscription"

    /** One row per (topic, producing runtime login), generated from topics.yaml; read by [EVENTS_MISSING] only. */
    const val PRODUCER = "producer"

    /** One row per record topic: its keeper login and the keeper's committed position (INV-REQ-1205 b4). */
    const val RECORD_POSITION = "record_position"

    /** The one-row retention table: content window and event-log retention (coordinator ruling INV-Q-4262). */
    const val RETENTION = "retention"

    /** `claim(topic, after_offset, limit)`: (log_offset, tenant_id, inserted_at) of a subscribed topic, across tenants. */
    const val CLAIM = "claim"

    /** `purge_batch()`: one age-only purge batch on every topic, raising each watermark in the same transaction. */
    const val PURGE_BATCH = "purge_batch"

    /** `record_position(topic, offset)`: the keeper's forward-only position write on a record topic. */
    const val RECORD_POSITION_FUNCTION = "record_position"

    /**
     * `events_missing(topic, event_ids)`: of up to [MAX_EVENTS_MISSING] event ids, those not in a topic the caller
     * produces, and the topic's retention cut-off (owner ruling INV-Q-4350 (A)).
     */
    const val EVENTS_MISSING = "events_missing"

    /** The most event ids [EVENTS_MISSING] accepts in one call. */
    const val MAX_EVENTS_MISSING = 1_000

    /** The largest `limit` [CLAIM] accepts. */
    const val MAX_CLAIM = 10_000

    /** The rows [PURGE_BATCH] deletes per topic in one call, at most. */
    const val PURGE_BATCH_SIZE = 1_000

    /** The tenant policy's name on every topic table (policy names are per table, so one fixed name fits all). */
    const val TENANT_POLICY = "tenant_isolation"

    /**
     * One topic of topics.yaml as the event-log migration sees it: the runtime logins that produce and subscribe to it,
     * its `content_topic` marker (content window or event-log retention), and for a record topic the [keeper], the one
     * subscriber whose committed position holds the purge back (evidence-svc's runtime login, INV-Q-3627).
     */
    data class Topic(
        val name: String,
        val producers: Set<String>,
        val subscribers: Set<String>,
        val contentTopic: Boolean,
        val keeper: String? = null,
    ) {
        init {
            tableName(name)
            (producers + subscribers).forEach { SqlIdentifier.require(it, "runtime login") }
            if (keeper != null) {
                SqlIdentifier.require(keeper, "keeper login")
                require(
                    keeper in subscribers,
                ) { "the keeper of record topic $name must subscribe to it, was '$keeper'" }
            }
        }

        val record: Boolean get() = keeper != null
    }

    /** The table of [topic]: dots and hyphens become underscores (`gst.a.b-c.v1` is `gst_a_b_c_v1`). */
    fun tableName(topic: String): String {
        require(TOPIC.matches(topic)) { "topic must look like gst.<service>.<event-name>.v<N>, was '$topic'" }
        return SqlIdentifier.require(topic.replace('.', '_').replace('-', '_'), "topic table")
    }

    /**
     * The tables the event-log jOOQ classes use, by `DSL.name(schema, table)` with their class for tenancy-jooq
     * (INV-T-4133): each of [topics] is a topic table, tenant data (`TENANT`, [topicDdl]); [EVENTS_MISSING] is a
     * function over the event log's own tables for the keeper's login, declared `PLATFORM_SCOPED`.
     */
    fun jooqTables(topics: Collection<String>): Map<Name, TableClass> =
        topics.associate { DSL.name(SCHEMA, tableName(it)) to TableClass.TENANT } +
            (DSL.name(SCHEMA, EVENTS_MISSING) to TableClass.PLATFORM_SCOPED)

    /**
     * The event log's tenantless tables, `PLATFORM_SCOPED` (INV-ADR-3968 item 4a, INV-T-4134), by qualified name: each
     * has the table comment `platform-scoped: no tenant data` in [schemaDdl]. A topic table ([topicDdl]) is tenant data.
     */
    fun platformScopedTables(): List<String> =
        listOf(SUBSCRIPTION, PRODUCER, PURGE_WATERMARK, RECORD_POSITION, RETENTION).map { "$SCHEMA.$it" }

    /**
     * The tables the functions keep their state in, with `eventlog_sys`'s grants and nothing for any runtime login:
     * [SUBSCRIPTION], [PRODUCER], [PURGE_WATERMARK] (subscribers get SELECT on their topics' rows in [functionsDdl]),
     * [RECORD_POSITION] and [RETENTION] (CHECK of at least 7 days on each value). Run once, before [topicDdl].
     */
    fun schemaDdl(sysRole: String): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create table $SCHEMA.$SUBSCRIPTION (
                topic_key text not null,
                login text not null,
                primary key (topic_key, login)
            );
            comment on table $SCHEMA.$SUBSCRIPTION is 'platform-scoped: no tenant data';
            create table $SCHEMA.$PRODUCER (
                topic_key text not null,
                login text not null,
                primary key (topic_key, login)
            );
            comment on table $SCHEMA.$PRODUCER is 'platform-scoped: no tenant data';
            create table $SCHEMA.$PURGE_WATERMARK (
                topic_key text primary key,
                watermark bigint not null check (watermark >= 0),
                updated_at timestamptz not null default now()
            );
            comment on table $SCHEMA.$PURGE_WATERMARK is 'platform-scoped: no tenant data';
            alter table $SCHEMA.$PURGE_WATERMARK enable row level security;
            alter table $SCHEMA.$PURGE_WATERMARK force row level security;
            create policy sys_purge on $SCHEMA.$PURGE_WATERMARK for all to $sys using (true) with check (true);
            create table $SCHEMA.$RECORD_POSITION (
                topic_key text primary key,
                keeper text not null,
                position bigint not null default 0 check (position >= 0),
                updated_at timestamptz not null default now()
            );
            comment on table $SCHEMA.$RECORD_POSITION is 'platform-scoped: no tenant data';
            create table $SCHEMA.$RETENTION (
                id boolean primary key default true check (id),
                content_window interval not null check (content_window >= interval '7 days'),
                eventlog_retention interval not null check (eventlog_retention >= interval '7 days'),
                updated_at timestamptz not null default now()
            );
            comment on table $SCHEMA.$RETENTION is 'platform-scoped: no tenant data';
            revoke all on $SCHEMA.$SUBSCRIPTION, $SCHEMA.$PRODUCER, $SCHEMA.$PURGE_WATERMARK, $SCHEMA.$RECORD_POSITION, $SCHEMA.$RETENTION
                from public;
            grant usage on schema $SCHEMA to $sys;
            grant select on $SCHEMA.$SUBSCRIPTION to $sys;
            grant select on $SCHEMA.$PRODUCER to $sys;
            grant select, insert, update on $SCHEMA.$PURGE_WATERMARK to $sys;
            grant select on $SCHEMA.$RECORD_POSITION to $sys;
            grant update (position, updated_at) on $SCHEMA.$RECORD_POSITION to $sys;
            grant select on $SCHEMA.$RETENTION to $sys;
            """.trimIndent()
    }

    /**
     * `CREATE TABLE` for [topic] with forced tenant row-level security; INSERT for each producer's runtime login and
     * SELECT under the tenant policy for each subscriber's; and for [sysRole] the cross-tenant `SELECT` of `log_offset`,
     * `tenant_id` and `inserted_at` ([CLAIM], [PURGE_BATCH]) and of `event_id` ([EVENTS_MISSING], over the topic's
     * `event_id` index), and `DELETE` ([PURGE_BATCH]). Run [schemaDdl] first.
     */
    fun topicDdl(
        topic: Topic,
        sysRole: String,
    ): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(sys !in topic.producers + topic.subscribers) { "the sys role is no runtime login, was '$sys'" }
        val table =
            """
            create table $SCHEMA.$topicKey (
                log_offset bigint generated always as identity primary key,
                tenant_id uuid not null,
                event_id text not null,
                subject text not null,
                envelope jsonb not null,
                inserted_at timestamptz not null default now()
            );
            comment on column $SCHEMA.$topicKey.envelope is 'class: content';
            create index ${eventIdIndex(topic)} on $SCHEMA.$topicKey (event_id);
            create policy sys_claim on $SCHEMA.$topicKey for select to $sys using (true);
            create policy sys_purge on $SCHEMA.$topicKey for delete to $sys using (true);
            grant usage on schema $SCHEMA to $sys;
            grant select (log_offset, tenant_id, inserted_at, event_id) on $SCHEMA.$topicKey to $sys;
            grant delete on $SCHEMA.$topicKey to $sys;
            """.trimIndent()
        return listOfNotNull(
            table,
            TenantRlsMigration.ddl(SCHEMA, topicKey, policyName = TENANT_POLICY),
            if (topic.producers.isEmpty()) null else producerGrants(topic),
            if (topic.subscribers.isEmpty()) null else subscriberGrants(topic),
        ).joinToString("\n")
    }

    private fun producerGrants(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val producerLogins = topic.producers.sorted().map { SqlIdentifier.require(it, "producer login") }
        return """
            grant usage on schema $SCHEMA to ${producerLogins.joinToString(", ")};
            grant insert on $SCHEMA.$topicKey to ${producerLogins.joinToString(", ")};
            """.trimIndent()
    }

    private fun subscriberGrants(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val subscriberLogins = topic.subscribers.sorted().map { SqlIdentifier.require(it, "subscriber login") }
        return """
            grant usage on schema $SCHEMA to ${subscriberLogins.joinToString(", ")};
            grant select on $SCHEMA.$topicKey to ${subscriberLogins.joinToString(", ")};
            """.trimIndent()
    }

    /**
     * Regenerates, for the whole topic set [topics], the [SUBSCRIPTION] and [PRODUCER] rows, the [RECORD_POSITION] rows (keeper from
     * [Topic.keeper]; an existing position is kept), each subscriber's `SELECT` on its topics' [PURGE_WATERMARK] rows,
     * and the four functions, each dropped and created again, handed to [sysRole], with EXECUTE revoked from PUBLIC
     * and granted to the subscribers ([CLAIM]), [purgeLogin] ([PURGE_BATCH]), the keepers
     * ([RECORD_POSITION_FUNCTION]) and the producers ([EVENTS_MISSING]) only. Run after [topicDdl] of every topic, in one migration transaction.
     *
     * @throws IllegalArgumentException when two topics share a name or a [tableName] (the key must be injective), or a
     *   login is not a plain identifier or is the sys role.
     */
    fun functionsDdl(
        topics: List<Topic>,
        sysRole: String,
        purgeLogin: String,
    ): String {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val purge = SqlIdentifier.require(purgeLogin, "purge login")
        require(purge != sys) { "the purge login is a runtime login, not the sys role" }
        val ordered = topics.sortedBy { it.name }
        require(ordered.map { it.name }.toSet().size == ordered.size) { "a topic is listed twice" }
        val keys = ordered.groupBy { tableName(it.name) }.filterValues { it.size > 1 }
        require(keys.isEmpty()) { "topics share an event-log key, so the key is not injective: $keys" }
        val policies = ordered.groupBy { watermarkPolicy(it) }.filterValues { it.size > 1 }
        require(policies.isEmpty()) { "topics share a watermark policy name after truncation: $policies" }
        require(ordered.none { sys in it.producers + it.subscribers }) { "the sys role is no runtime login" }
        val indexes = ordered.groupBy { eventIdIndex(it) }.filterValues { it.size > 1 }
        require(indexes.isEmpty()) { "topics share an event_id index name after truncation: $indexes" }
        val subscribed = ordered.filter { it.subscribers.isNotEmpty() }
        val produced = ordered.filter { it.producers.isNotEmpty() }
        val records = ordered.filter { it.record }
        return (
            listOf("delete from $SCHEMA.$SUBSCRIPTION;") +
                subscribed.map { subscriptionRows(it) } +
                listOf("delete from $SCHEMA.$PRODUCER;") +
                produced.map { producerRows(it) } +
                listOf(
                    if (records.isEmpty()) "delete from $SCHEMA.$RECORD_POSITION;" else staleRecordPositions(records),
                ) +
                records.map { recordPositionRow(it) } +
                ordered.map { watermarkReads(it) } +
                claimFunction(ordered, sysRole) +
                purgeFunction(ordered, sysRole, purgeLogin) +
                recordPositionFunction(records, sysRole) +
                eventsMissingFunction(ordered, sysRole)
        ).joinToString("\n")
    }

    /**
     * The migration step that writes the retention row from the installation profile with bound parameters, re-applied
     * at every install and upgrade (coordinator ruling INV-Q-4262): [Retention.contentWindow] for content topics and
     * [Retention.eventLogRetention] for the others, each at least 7 days ([Retention] refuses less; default 30 days).
     */
    fun writeRetention(
        connection: Connection,
        retention: Retention = Retention(),
    ) {
        connection
            .prepareStatement(
                """
                insert into $SCHEMA.$RETENTION (id, content_window, eventlog_retention, updated_at)
                values (true, ? * interval '1 second', ? * interval '1 second', now())
                on conflict (id) do update set content_window = excluded.content_window,
                    eventlog_retention = excluded.eventlog_retention, updated_at = excluded.updated_at
                """.trimIndent(),
            ).use {
                it.setLong(1, retention.contentWindow.seconds)
                it.setLong(2, retention.eventLogRetention.seconds)
                it.executeUpdate()
            }
    }

    private fun subscriptionRows(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val subscriberLogins = topic.subscribers.sorted().map { SqlIdentifier.require(it, "subscriber login") }
        return """
            insert into $SCHEMA.$SUBSCRIPTION (topic_key, login)
            select '$topicKey', unnest(array['${subscriberLogins.joinToString("', '")}']::text[]);
            """.trimIndent()
    }

    private fun producerRows(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val producerLogins = topic.producers.sorted().map { SqlIdentifier.require(it, "producer login") }
        return """
            insert into $SCHEMA.$PRODUCER (topic_key, login)
            select '$topicKey', unnest(array['${producerLogins.joinToString("', '")}']::text[]);
            """.trimIndent()
    }

    /** Removes the record-position rows of topics no longer marked as record topics. */
    private fun staleRecordPositions(records: List<Topic>): String {
        val recordKeys = records.map { SqlIdentifier.require(tableName(it.name), "topic table") }
        return """
            delete from $SCHEMA.$RECORD_POSITION
            where topic_key <> all(array['${recordKeys.joinToString("', '")}']::text[]);
            """.trimIndent()
    }

    private fun recordPositionRow(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val keeperLogin = SqlIdentifier.require(checkNotNull(topic.keeper), "keeper login")
        return """
            insert into $SCHEMA.$RECORD_POSITION (topic_key, keeper) values ('$topicKey', '$keeperLogin')
            on conflict (topic_key) do update set keeper = excluded.keeper;
            """.trimIndent()
    }

    /** Each subscriber reads its topic's watermark row and no other (INV-REQ-1205 b3, b6). */
    private fun watermarkReads(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val policy = SqlIdentifier.require(watermarkPolicy(topic), "watermark policy")
        val drop = "drop policy if exists $policy on $SCHEMA.$PURGE_WATERMARK;"
        if (topic.subscribers.isEmpty()) return drop
        val subscriberLogins = topic.subscribers.sorted().map { SqlIdentifier.require(it, "subscriber login") }
        return """
            drop policy if exists $policy on $SCHEMA.$PURGE_WATERMARK;
            create policy $policy on $SCHEMA.$PURGE_WATERMARK for select to ${subscriberLogins.joinToString(", ")}
                using (topic_key = '$topicKey');
            grant select on $SCHEMA.$PURGE_WATERMARK to ${subscriberLogins.joinToString(", ")};
            """.trimIndent()
    }

    private fun eventIdIndex(topic: Topic): String =
        SqlIdentifier.require(
            tableName(topic.name).take(MAX_IDENTIFIER - EVENT_ID_SUFFIX.length) + EVENT_ID_SUFFIX,
            "event_id index",
        )

    private fun watermarkPolicy(topic: Topic): String =
        SqlIdentifier.require("wm_${tableName(topic.name)}".take(MAX_IDENTIFIER), "watermark policy")

    /**
     * [CLAIM]: refuses a malformed topic, a negative offset and a limit outside 1..[MAX_CLAIM]; returns nothing unless
     * `session_user` subscribes to the topic ([SUBSCRIPTION]); then the claim columns of the topic's branch. STABLE, so a
     * caller reading its watermark in the same statement reads it in the same snapshot.
     */
    private fun claimFunction(
        topics: List<Topic>,
        sysRole: String,
    ): List<String> {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val subscriberNames = topics.flatMap { it.subscribers }.distinct().sorted()
        val subscriberLogins = subscriberNames.map { SqlIdentifier.require(it, "subscriber login") }
        val head =
            """
            drop function if exists $SCHEMA.$CLAIM(text, bigint, integer);
            create function $SCHEMA.$CLAIM(p_topic text, p_after_offset bigint, p_limit integer)
            returns table (log_offset bigint, tenant_id uuid, inserted_at timestamptz)
            language plpgsql stable security definer set search_path = pg_catalog, $SCHEMA, pg_temp as $$
            declare
                v_key text;
            begin
                if p_topic is null or p_topic !~ '^[a-z][a-z0-9.-]*$' or p_after_offset is null or p_after_offset < 0
                    or p_limit is null or p_limit < 1 or p_limit > $MAX_CLAIM then
                    raise exception 'a claim names a topic, an offset of at least 0 and a limit of 1..$MAX_CLAIM'
                        using errcode = '22023';
                end if;
                v_key := translate(p_topic, '.-', '__');
                if not exists (
                    select 1 from $SCHEMA.$SUBSCRIPTION s where s.topic_key = v_key and s.login = session_user
                ) then
                    return;
                end if;
                if false then
                    return;
            """.trimIndent()
        val tail =
            """
                end if;
            end
            $$;
            """.trimIndent()
        val handOver =
            """
            grant create on schema $SCHEMA to $sys;
            alter function $SCHEMA.$CLAIM(text, bigint, integer) owner to $sys;
            revoke all on function $SCHEMA.$CLAIM(text, bigint, integer) from public;
            revoke create on schema $SCHEMA from $sys;
            """.trimIndent()
        val grant =
            if (subscriberLogins.isEmpty()) {
                null
            } else {
                """
                grant execute on function $SCHEMA.$CLAIM(text, bigint, integer) to ${subscriberLogins.joinToString(
                    ", ",
                )};
                """.trimIndent()
            }
        return listOfNotNull(head) + topics.map { claimBranch(it) } + listOfNotNull(tail, handOver, grant)
    }

    private fun claimBranch(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        return """
            elsif v_key = '$topicKey' then
                return query
                    select t.log_offset, t.tenant_id, t.inserted_at from $SCHEMA.$topicKey t
                    where t.log_offset > p_after_offset order by t.log_offset limit p_limit;
            """.trimIndent()
    }

    /**
     * [PURGE_BATCH]: reads the retention row and, when it is missing or a value is below 7 days, deletes nothing and
     * raises a warning (no row returned). Otherwise, per topic, deletes up to [PURGE_BATCH_SIZE] rows inserted before
     * `now() - <period>` (the content window for a content topic, the event-log retention for any other), lowest offsets
     * first and, on a record topic, none above the keeper's recorded position; raises the topic's watermark to the
     * highest offset deleted (never lowers it) in the same transaction; and returns one row per topic.
     */
    private fun purgeFunction(
        topics: List<Topic>,
        sysRole: String,
        purgeLogin: String,
    ): List<String> {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val purge = SqlIdentifier.require(purgeLogin, "purge login")
        val head =
            """
            drop function if exists $SCHEMA.$PURGE_BATCH();
            create function $SCHEMA.$PURGE_BATCH()
            returns table (purged_topic text, purged_content boolean, purged_period interval, purged_cutoff timestamptz,
                purged_rows bigint, purged_watermark bigint)
            language plpgsql security definer set search_path = pg_catalog, $SCHEMA, pg_temp as $$
            declare
                v_content interval;
                v_retention interval;
                v_position bigint;
                v_max bigint;
            begin
                select r.content_window, r.eventlog_retention into v_content, v_retention
                from $SCHEMA.$RETENTION r where r.id;
                if not found or v_content is null or v_retention is null
                    or v_content < interval '7 days' or v_retention < interval '7 days' then
                    raise warning 'event-log retention is missing or below 7 days: nothing is purged (INV-Q-4262)';
                    return;
                end if;
            """.trimIndent()
        val tail =
            """
            end
            $$;
            """.trimIndent()
        val handOver =
            """
            grant create on schema $SCHEMA to $sys;
            alter function $SCHEMA.$PURGE_BATCH() owner to $sys;
            revoke all on function $SCHEMA.$PURGE_BATCH() from public;
            grant usage on schema $SCHEMA to $purge;
            grant execute on function $SCHEMA.$PURGE_BATCH() to $purge;
            revoke create on schema $SCHEMA from $sys;
            """.trimIndent()
        return listOf(head) + topics.map { purgeBranch(it) } + listOf(tail, handOver)
    }

    /** A record topic's purge stops at its keeper's recorded position (INV-REQ-1205 b4). */
    private const val READ_RECORD_POSITION =
        "select coalesce(max(p.position), 0) into v_position from $SCHEMA.$RECORD_POSITION p where p.topic_key = purged_topic;"

    /** Any other topic has no position to respect. */
    private const val NO_RECORD_POSITION = "v_position := null;"

    private fun purgeBranch(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val content = if (topic.contentTopic) "true" else "false"
        val period = if (topic.contentTopic) "v_content" else "v_retention"
        val position = if (topic.record) READ_RECORD_POSITION else NO_RECORD_POSITION
        return """
            purged_topic := '$topicKey';
            purged_content := $content;
            purged_period := $period;
            purged_cutoff := now() - $period;
            $position
            with gone as (
                delete from $SCHEMA.$topicKey t where t.log_offset in (
                    select c.log_offset from $SCHEMA.$topicKey c
                    where c.inserted_at < purged_cutoff and (v_position is null or c.log_offset <= v_position)
                    order by c.log_offset limit $PURGE_BATCH_SIZE
                ) returning t.log_offset
            )
            select count(*), max(g.log_offset) into purged_rows, v_max from gone g;
            if purged_rows > 0 then
                insert into $SCHEMA.$PURGE_WATERMARK as w (topic_key, watermark, updated_at)
                values ('$topicKey', v_max, now())
                on conflict (topic_key) do update
                    set watermark = greatest(w.watermark, excluded.watermark), updated_at = excluded.updated_at;
            end if;
            select coalesce(max(w.watermark), 0) into purged_watermark
            from $SCHEMA.$PURGE_WATERMARK w where w.topic_key = '$topicKey';
            return next;
            """.trimIndent()
    }

    /**
     * [RECORD_POSITION_FUNCTION]: refuses a malformed topic and a negative offset; refuses (`42501`) unless the topic has
     * a record-position row whose keeper is `session_user`; moves the position forward only (a lower offset leaves it)
     * and returns the stored position.
     */
    private fun recordPositionFunction(
        records: List<Topic>,
        sysRole: String,
    ): List<String> {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val keeperLogins =
            records.map { checkNotNull(it.keeper) }.distinct().sorted().map {
                SqlIdentifier.require(it, "keeper login")
            }
        val function =
            """
            drop function if exists $SCHEMA.$RECORD_POSITION_FUNCTION(text, bigint);
            create function $SCHEMA.$RECORD_POSITION_FUNCTION(p_topic text, p_offset bigint) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $SCHEMA, pg_temp as $$
            declare
                v_key text;
                v_keeper text;
                v_position bigint;
            begin
                if p_topic is null or p_topic !~ '^[a-z][a-z0-9.-]*$' or p_offset is null or p_offset < 0 then
                    raise exception 'a record position names a topic and an offset of at least 0' using errcode = '22023';
                end if;
                v_key := translate(p_topic, '.-', '__');
                select r.keeper into v_keeper from $SCHEMA.$RECORD_POSITION r where r.topic_key = v_key for update;
                if not found or v_keeper is distinct from session_user then
                    raise exception 'only the keeper of a record topic records its position' using errcode = '42501';
                end if;
                update $SCHEMA.$RECORD_POSITION r set position = greatest(r.position, p_offset), updated_at = now()
                where r.topic_key = v_key
                returning r.position into v_position;
                return v_position;
            end
            $$;
            """.trimIndent()
        val handOver =
            """
            grant create on schema $SCHEMA to $sys;
            alter function $SCHEMA.$RECORD_POSITION_FUNCTION(text, bigint) owner to $sys;
            revoke all on function $SCHEMA.$RECORD_POSITION_FUNCTION(text, bigint) from public;
            revoke create on schema $SCHEMA from $sys;
            """.trimIndent()
        val grant =
            if (keeperLogins.isEmpty()) {
                null
            } else {
                """
                grant execute on function $SCHEMA.$RECORD_POSITION_FUNCTION(text, bigint) to ${keeperLogins.joinToString(
                    ", ",
                )};
                """.trimIndent()
            }
        return listOfNotNull(function, handOver, grant)
    }

    /**
     * [EVENTS_MISSING]: refuses a malformed topic, a null array or one of more than [MAX_EVENTS_MISSING] ids (`22023`);
     * refuses (`42501`) unless `session_user` produces the topic ([PRODUCER]); refuses (`55000`) without a retention
     * row of at least 7 days. Otherwise returns one row: the distinct given ids (nulls ignored) not present in the
     * topic, and the topic's retention cut-off, `now()` less its period (the content window for a content topic, the
     * event-log retention for any other), the same cut-off [PURGE_BATCH] deletes before. Nothing else of the topic.
     */
    private fun eventsMissingFunction(
        topics: List<Topic>,
        sysRole: String,
    ): List<String> {
        val sys = SqlIdentifier.require(sysRole, "sys role")
        val producerNames = topics.flatMap { it.producers }.distinct().sorted()
        val producerLogins = producerNames.map { SqlIdentifier.require(it, "producer login") }
        val head =
            """
            drop function if exists $SCHEMA.$EVENTS_MISSING(text, text[]);
            create function $SCHEMA.$EVENTS_MISSING(p_topic text, p_event_ids text[], out missing text[],
                out cutoff timestamptz)
            language plpgsql stable security definer set search_path = pg_catalog, $SCHEMA, pg_temp as $$
            declare
                v_key text;
                v_content interval;
                v_retention interval;
            begin
                if p_topic is null or p_topic !~ '^[a-z][a-z0-9.-]*$' or p_event_ids is null
                    or coalesce(cardinality(p_event_ids), 0) > $MAX_EVENTS_MISSING then
                    raise exception 'events_missing names a topic and at most $MAX_EVENTS_MISSING event ids'
                        using errcode = '22023';
                end if;
                v_key := translate(p_topic, '.-', '__');
                if not exists (
                    select 1 from $SCHEMA.$PRODUCER p where p.topic_key = v_key and p.login = session_user
                ) then
                    raise exception 'events_missing is open to the producers of a topic only' using errcode = '42501';
                end if;
                select r.content_window, r.eventlog_retention into v_content, v_retention
                from $SCHEMA.$RETENTION r where r.id;
                if not found or v_content is null or v_retention is null
                    or v_content < interval '7 days' or v_retention < interval '7 days' then
                    raise exception 'event-log retention is missing or below 7 days (INV-Q-4262)' using errcode = '55000';
                end if;
                if false then
                    return;
            """.trimIndent()
        val tail =
            """
                end if;
            end
            $$;
            """.trimIndent()
        val handOver =
            """
            grant create on schema $SCHEMA to $sys;
            alter function $SCHEMA.$EVENTS_MISSING(text, text[]) owner to $sys;
            revoke all on function $SCHEMA.$EVENTS_MISSING(text, text[]) from public;
            revoke create on schema $SCHEMA from $sys;
            """.trimIndent()
        val grant =
            if (producerLogins.isEmpty()) {
                null
            } else {
                """
                grant execute on function $SCHEMA.$EVENTS_MISSING(text, text[]) to ${producerLogins.joinToString(", ")};
                """.trimIndent()
            }
        return listOf(head) + topics.map { eventsMissingBranch(it) } + listOfNotNull(tail, handOver, grant)
    }

    private fun eventsMissingBranch(topic: Topic): String {
        val topicKey = SqlIdentifier.require(tableName(topic.name), "topic table")
        val period = if (topic.contentTopic) "v_content" else "v_retention"
        return """
            elsif v_key = '$topicKey' then
                cutoff := now() - $period;
                missing := array(
                    select distinct i.id from unnest(p_event_ids) as i(id)
                    where i.id is not null
                        and not exists (select 1 from $SCHEMA.$topicKey t where t.event_id = i.id)
                    order by i.id
                );
            """.trimIndent()
    }

    private const val MAX_IDENTIFIER = 63
    private const val EVENT_ID_SUFFIX = "_event_id"
    private val TOPIC = Regex("^[a-z][a-z0-9.-]*$")
}
