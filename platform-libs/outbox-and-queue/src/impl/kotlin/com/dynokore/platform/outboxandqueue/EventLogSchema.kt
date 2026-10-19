package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.jooq.DSLContext
import org.jooq.Name
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.types.DayToSecond

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
 *   ([TenantRlsMigration.ddl]). A producer's runtime login holds INSERT on its own topics, the tenant policy's WITH
 * CHECK
 *   binding each row to the transaction's tenant; a subscriber's runtime login holds SELECT under the tenant policy and
 *   SELECT on its topics' purge-watermark rows. No runtime login reads across tenants.
 * - Every cross-tenant operation runs through the SECURITY DEFINER functions [CLAIM], [PURGE_BATCH],
 *   [RECORD_POSITION_FUNCTION] and [EVENTS_MISSING] (owner ruling INV-Q-4350 (A)), owned by `eventlog_sys`, with static
 * SQL (one branch per topic, generated here; no
 *   `EXECUTE`), `SET search_path = pg_catalog, eventlog, pg_temp`, EXECUTE revoked from PUBLIC and granted only to the
 *   runtime logins that need each. `eventlog_sys` alone holds the cross-tenant policies and column grants they use.
 *
 * Rows of the [SUBSCRIPTION], [PRODUCER], [PURGE_WATERMARK] and [RECORD_POSITION] tables are keyed by one mapping, the
 * topic's
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

    /**
     * `claim(topic, after_offset, limit)`: (log_offset, tenant_id, inserted_at) of a subscribed topic, across tenants.
     */
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
     * its `content_topic` marker (content window or event-log retention), its `retained_while_resolvable` marker (the
     * pack artefact retention, INV-REQ-1210, INV-Q-6281; never together with `content_topic`), and for a record topic
     * the [keeper], the one
     * subscriber whose committed position holds the purge back (evidence-svc's runtime login, INV-Q-3627).
     */
    data class Topic(
        val name: String,
        val producers: Set<String>,
        val subscribers: Set<String>,
        val contentTopic: Boolean,
        val keeper: String? = null,
        val retainedWhileResolvable: Boolean = false,
    ) {
        init {
            require(!(contentTopic && retainedWhileResolvable)) {
                "topic $name is marked both content_topic and retained_while_resolvable"
            }
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
     * function over the event log's own tables for the keeper's login, declared `PLATFORM_SCOPED`; so are [CLAIM] and
     * [PURGE_BATCH] (sys-owned functions that answer across tenants, the function is the grant), and the tenantless
     * tables [PURGE_WATERMARK] and [RETENTION] ([platformScopedTables]).
     */
    fun jooqTables(topics: Collection<String>): Map<Name, TableClass> =
        topics.associate { DSL.name(SCHEMA, tableName(it)) to TableClass.TENANT } +
            (DSL.name(SCHEMA, EVENTS_MISSING) to TableClass.PLATFORM_SCOPED) +
            (DSL.name(SCHEMA, CLAIM) to TableClass.PLATFORM_SCOPED) +
            (DSL.name(SCHEMA, PURGE_BATCH) to TableClass.PLATFORM_SCOPED) +
            (DSL.name(SCHEMA, PURGE_WATERMARK) to TableClass.PLATFORM_SCOPED) +
            (DSL.name(SCHEMA, RETENTION) to TableClass.PLATFORM_SCOPED)

    /**
     * The event log's tenantless tables, `PLATFORM_SCOPED` (INV-ADR-3968 item 4a, INV-T-4134), by qualified name: each
     * has the table comment `platform-scoped: no tenant data` in [schemaDdl]. A topic table ([topicDdl]) is tenant
     * data.
     */
    fun platformScopedTables(): List<String> =
        listOf(SUBSCRIPTION, PRODUCER, PURGE_WATERMARK, RECORD_POSITION, RETENTION).map { "$SCHEMA.$it" }

    /**
     * The tables the functions keep their state in, with `eventlog_sys`'s grants and nothing for any runtime login:
     * [SUBSCRIPTION], [PRODUCER], [PURGE_WATERMARK] (subscribers get SELECT on their topics' rows in [functionsDdl]),
     * [RECORD_POSITION] and [RETENTION] (CHECK of at least 7 days on each value). Run once, before [topicDdl].
     */
    fun schemaDdl(sysRole: String): String =
        listOf(
            EventLogRowsSql.membershipTables(),
            EventLogRowsSql.watermarkTable(sysRole),
            EventLogRowsSql.stateTables(sysRole),
        ).joinToString("\n")

    /**
     * `CREATE TABLE` for [topic] with forced tenant row-level security; INSERT for each producer's runtime login and
     * SELECT under the tenant policy for each subscriber's; and for [sysRole] the cross-tenant `SELECT` of
     * `log_offset`,
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
            if (topic.producers.isEmpty()) null else EventLogRowsSql.producerGrants(topic),
            if (topic.subscribers.isEmpty()) null else EventLogRowsSql.subscriberGrants(topic),
        ).joinToString("\n")
    }

    /**
     * Regenerates, for the whole topic set [topics], the [SUBSCRIPTION] and [PRODUCER] rows, the [RECORD_POSITION] rows
     * (keeper from
     * [Topic.keeper]; an existing position is kept), each subscriber's `SELECT` on its topics' [PURGE_WATERMARK] rows,
     * and the four functions, each dropped and created again, handed to [sysRole], with EXECUTE revoked from PUBLIC
     * and granted to the subscribers ([CLAIM]), [purgeLogin] ([PURGE_BATCH]), the keepers
     * ([RECORD_POSITION_FUNCTION]) and the producers ([EVENTS_MISSING]) only. Run after [topicDdl] of every topic, in
     * one migration transaction.
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
                subscribed.map { EventLogRowsSql.subscriptionRows(it) } +
                listOf("delete from $SCHEMA.$PRODUCER;") +
                produced.map { EventLogRowsSql.producerRows(it) } +
                listOf(
                    if (records.isEmpty()) {
                        "delete from $SCHEMA.$RECORD_POSITION;"
                    } else {
                        EventLogRowsSql
                            .staleRecordPositions(
                                records,
                            )
                    },
                ) +
                records.map { EventLogRowsSql.recordPositionRow(it) } +
                ordered.map { EventLogRowsSql.watermarkReads(it, watermarkPolicy(it)) } +
                EventLogFunctionsSql.claim(ordered, sysRole) +
                EventLogPurgeSql.purge(ordered, sysRole, purgeLogin) +
                EventLogPositionSql.recordPosition(records, sysRole) +
                EventLogMissingSql.eventsMissing(ordered, sysRole)
        ).joinToString("\n")
    }

    /**
     * The DDL the migration runs at every install and upgrade, before [writeRetention] (INV-T-6291): adds the
     * `pack_retention` column to a retention table created before it existed, with the default and check of
     * [schemaDdl]; a no-op where the column exists. Run as the schema owner, like [schemaDdl].
     */
    fun retentionUpgradeDdl(): String =
        "alter table $SCHEMA.$RETENTION add column if not exists pack_retention interval not null " +
            "default interval '1827 days' check (pack_retention >= interval '1827 days')"

    /**
     * The migration step that writes the retention row from the installation profile with bound parameters, re-applied
     * at every install and upgrade (coordinator ruling INV-Q-4262): [Retention.contentWindow] for content topics and
     * [Retention.eventLogRetention] for the others, each at least 7 days ([Retention] refuses less; default 30 days),
     * and
     * [Retention.packArtefactRetention] for a topic marked `retained_while_resolvable` (at least 1827 days). The
     * column exists once [retentionUpgradeDdl] has run; the migration runs it just before this step, as a tenancy-jooq
     * context runs no DDL.
     */
    fun writeRetention(
        dsl: DSLContext,
        retention: Retention = Retention(),
    ) {
        val table = DSL.table(DSL.name(SCHEMA, RETENTION))
        val contentWindow = DSL.field(DSL.name("content_window"), SQLDataType.INTERVALDAYTOSECOND)
        val eventLogRetention = DSL.field(DSL.name("eventlog_retention"), SQLDataType.INTERVALDAYTOSECOND)
        val packRetention = DSL.field(DSL.name("pack_retention"), SQLDataType.INTERVALDAYTOSECOND)
        val updatedAt = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        dsl
            .insertInto(
                table,
                DSL.field(DSL.name("id"), SQLDataType.BOOLEAN),
                contentWindow,
                eventLogRetention,
                packRetention,
                updatedAt,
            ).values(
                DSL.`val`(true, SQLDataType.BOOLEAN),
                DSL.`val`(DayToSecond.valueOf(retention.contentWindow), SQLDataType.INTERVALDAYTOSECOND),
                DSL.`val`(DayToSecond.valueOf(retention.eventLogRetention), SQLDataType.INTERVALDAYTOSECOND),
                DSL.`val`(DayToSecond.valueOf(retention.packArtefactRetention), SQLDataType.INTERVALDAYTOSECOND),
                DSL.currentOffsetDateTime(),
            ).onConflict(DSL.field(DSL.name("id")))
            .doUpdate()
            .set(contentWindow, DSL.excluded(contentWindow))
            .set(eventLogRetention, DSL.excluded(eventLogRetention))
            .set(packRetention, DSL.excluded(packRetention))
            .set(updatedAt, DSL.excluded(updatedAt))
            .execute()
    }

    private fun eventIdIndex(topic: Topic): String =
        SqlIdentifier.require(
            tableName(topic.name).take(MAX_IDENTIFIER - EVENT_ID_SUFFIX.length) + EVENT_ID_SUFFIX,
            "event_id index",
        )

    private fun watermarkPolicy(topic: Topic): String =
        SqlIdentifier.require("wm_${tableName(topic.name)}".take(MAX_IDENTIFIER), "watermark policy")

    private const val MAX_IDENTIFIER = 63
    private const val EVENT_ID_SUFFIX = "_event_id"
    private val TOPIC = Regex("^[a-z][a-z0-9.-]*$")
}
