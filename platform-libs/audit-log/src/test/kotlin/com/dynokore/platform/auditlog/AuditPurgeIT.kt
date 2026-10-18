package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.APP
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture.Companion.SYS
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Stream

/**
 * The audited purge of `audit_entry` ([AuditSchema.purgeDdl]; INV-T-3984, INV-API-3822) on PostgreSQL 17 and 18, for a
 * fixture `control-plane-svc` (schema `controlplane`, runtime login `outbox_app`, NOLOGIN `outbox_sys`). The purge
 * entry is staged through [LocalAuditWriter.stage] (coordinator ruling INV-Q-4277) and the function is called as the
 * runtime login under `bindPlatform(actor)`, which tenancy-kernel's runner signs (INV-Q-3997, INV-T-5361).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditPurgeIT {
    private val fixtures = mutableMapOf<String, Purge>()

    // One container per image and provisioning variant: the grants and policies a test needs are init SQL (route R1).
    private fun purge(
        image: String,
        extraInit: String = "",
    ): Purge = fixtures.getOrPut("$image|$extraInit") { Purge(image, extraInit) }.also { it.reset() }

    @AfterAll
    fun close() = fixtures.values.forEach { it.close() }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the purge writes its own entry and deletes only the entries older than the cut-off`(image: String) {
        val p = purge(image)
        val tenant = UUID.randomUUID()
        p.seed(PLATFORM, p.yearsAgo(7))
        p.seed(PLATFORM, p.yearsAgo(6))
        p.seed(tenant, p.yearsAgo(8))
        p.seed(PLATFORM, p.yearsAgo(1))
        p.seed(tenant, p.now.minusSeconds(DAY))
        val cutOff = p.yearsAgo(5).minusSeconds(DAY)
        val staged = p.stage()

        assertEquals(3L, p.call(PurgeCall.of(staged, cutOff)))

        assertEquals(3L, p.countAll("select count(*) from controlplane.audit_entry"))
        val older = "select count(*) from controlplane.audit_entry where recorded_at < ?::timestamptz"
        assertEquals(0L, p.countAll(older, p.at(cutOff)))
        val own =
            "select count(*) from controlplane.audit_entry where id = ?::uuid and tenant_id = ? and entity_ref = ? " +
                "and actor = 'operator-9' and action = 'purgeAuditEntries' " +
                "and scope = 'platform (control-plane-svc audit_entry)' and reason = 'idem-key-1' " +
                "and source = '/gst/control-plane-svc' and subject = 'audit-purge' " +
                "and instance_id = 'control-plane-svc-1' and correlation_id = 'corr-purge' " +
                "and causation_id = 'cmd-purge' and (prior_state->>'rowCount')::int = 3 " +
                "and (prior_state->>'oldest')::timestamptz = ?::timestamptz " +
                "and (prior_state->>'newest')::timestamptz = ?::timestamptz " +
                "and (prior_state->>'cutOff')::timestamptz = ?::timestamptz"
        assertEquals(
            1L,
            p.countAll(
                own,
                UUID.fromString(staged.envelope.id.value),
                TenantId.PLATFORM.value,
                EntityRef.PLATFORM.value,
                p.at(p.yearsAgo(8)),
                p.at(p.yearsAgo(6)),
                p.at(cutOff),
            ),
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a purge with nothing to delete still writes its entry, with no prior state`(image: String) {
        val p = purge(image)
        p.seed(PLATFORM, p.yearsAgo(1))
        val staged = p.stage()
        assertEquals(0L, p.call(PurgeCall.of(staged, p.yearsAgo(6))))
        assertEquals(2L, p.countAll("select count(*) from controlplane.audit_entry"))
        val prior = "select prior_state::text from controlplane.audit_entry where id = ?::uuid"
        assertNull(
            p
                .platformRows(prior, UUID.fromString(staged.envelope.id.value))
                .single()
                .values
                .single(),
        )
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a purge whose own entry cannot be written deletes nothing`(image: String) {
        val p = purge(image, NO_SYS_INSERT)
        p.seed(PLATFORM, p.yearsAgo(7))
        assertThrows(DataAccessException::class.java) { p.call(PurgeCall.of(p.stage(), p.yearsAgo(6))) }
        assertEquals(1L, p.countAll("select count(*) from controlplane.audit_entry"))
    }

    /**
     * The function stamps its own entry with `clock_timestamp()` (coordinator ruling INV-Q-4316): an entry staged
     * with a time older than the floor is stored at the database's time, so a later purge cannot erase it.
     */
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a backdated purge entry is stored at the database time and a later purge keeps it`(image: String) {
        val p = purge(image)
        p.seed(PLATFORM, p.yearsAgo(7))
        val first = p.stage(clock = Clock.fixed(p.yearsAgo(6), ZoneOffset.UTC))
        assertTrue(first.envelope.time < p.yearsAgo(5), "staged older than the floor")
        assertEquals(1L, p.call(PurgeCall.of(first, p.yearsAgo(6).minusSeconds(DAY))))
        p.call(PurgeCall.of(p.stage(), p.yearsAgo(5).minusSeconds(DAY)))
        val kept = "select count(*) from controlplane.audit_entry where id = ?::uuid and recorded_at > ?::timestamptz"
        val stays = p.countAll(kept, UUID.fromString(first.envelope.id.value), p.at(p.now))
        assertEquals(1L, stays, "the first purge's entry carries the database time and stays")
        assertEquals(2L, p.countAll("select count(*) from controlplane.audit_entry"))
    }

    /** One test per check of the function (INV-T-3984, INV-Q-4277): each refuses, and nothing is written or deleted. */
    @ParameterizedTest(name = "{1} [{0}]")
    @MethodSource("refusals")
    fun `a failed check refuses and deletes nothing`(
        image: String,
        check: String,
    ) {
        // The tenant check is the function's own: an extra sys policy lets RLS admit the platform-tenant entry, so
        // only the check can refuse the call (provisioned as init SQL of that variant).
        val p = purge(image, if (check == WRONG_TENANT) OPEN_SYS_INSERT else "")
        p.seed(PLATFORM, p.yearsAgo(7))
        p.seed(UUID.randomUUID(), p.yearsAgo(9))
        val staged = p.stage()
        val good = PurgeCall.of(staged, p.yearsAgo(6))
        if (check == ALREADY_PRESENT) p.seed(PLATFORM, p.now, id = staged.envelope.id.value)
        val failure = assertThrows(DataAccessException::class.java) { checkNotNull(REFUSALS[check])(p, good) }
        if (check == WRONG_TENANT) assertTrue("platform tenant only" in failure.message.orEmpty(), failure.message)
        val expected = if (check in PRIVILEGE_CHECKS) "42501" else "22023"
        assertEquals(expected, p.db.tx.sqlStateOf(failure), failure.message)
        val seeded = if (check == ALREADY_PRESENT) 3L else 2L
        assertEquals(seeded, p.countAll("select count(*) from controlplane.audit_entry"))
    }

    /**
     * A forged `app.binding_sig` gets the refusal the unbound case gets (INV-T-5361, INV-REQ-854 b7): the runner
     * signs under a key the schema does not hold, so `bound_actor()` is NULL, and the purge refuses with 42501 and
     * deletes nothing. Look-alike: a binding valid for `controlplane` does not pass the function of another schema.
     */
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a forged binding signature is refused like the unbound case, and one schema's binding does not pass another's`(
        image: String,
    ) {
        val p = purge(image)
        p.seed(PLATFORM, p.yearsAgo(7))
        val good = PurgeCall.of(p.stage(), p.yearsAgo(6))
        val unbound = assertThrows(DataAccessException::class.java) { p.call(good, unsetActor = true) }
        val forged = assertThrows(DataAccessException::class.java) { p.forgedCall(good) }
        val otherSchema = assertThrows(DataAccessException::class.java) { p.forgedCall(good, OTHER, p.db.tx) }
        listOf(unbound, forged, otherSchema).forEach {
            assertEquals("42501", p.db.tx.sqlStateOf(it), it.message)
            assertTrue("app.actor is not set" in it.message.orEmpty(), it.message)
        }
        assertEquals(1L, p.countAll("select count(*) from controlplane.audit_entry"))
        // The control: the same call under the schema's own key passes.
        assertEquals(1L, p.call(good))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the runtime login holds only SELECT and INSERT, cannot delete, and raw SQL cannot pass the floor`(
        image: String,
    ) {
        val p = purge(image)
        assertEquals(
            listOf("INSERT,SELECT"),
            p.db.strings(
                "select string_agg(privilege_type, ',' order by privilege_type) " +
                    "from information_schema.role_table_grants " +
                    "where grantee = '$APP' and table_schema = 'controlplane' and table_name = 'audit_entry'",
            ),
        )
        assertEquals(
            listOf(
                // The binding functions of tenancy-kernel's BindingKeyMigration; only the verifier is closed to $APP.
                "binding_verified|$SYS|$PATH|$SYS",
                "bound_actor|$SYS|$PATH|$APP,$SYS",
                "bound_tenant|$SYS|$PATH|$APP,$SYS",
                "purge_audit_entries|$SYS|$PATH|$APP,$SYS",
            ),
            p.db.strings(
                "select p.proname || '|' || pg_get_userbyid(p.proowner) || '|' || array_to_string(p.proconfig, ';') " +
                    "|| '|' || (select string_agg(distinct split_part(a::text, '=', 1), ',' " +
                    "order by split_part(a::text, '=', 1)) from unnest(p.proacl) a) " +
                    "from pg_proc p where p.pronamespace = 'controlplane'::regnamespace and p.prosecdef " +
                    "order by p.proname",
            ),
        )
        assertEquals(
            listOf("false"),
            p.db.strings("select has_schema_privilege('$SYS', 'controlplane', 'CREATE')::text"),
        )

        p.seed(PLATFORM, p.yearsAgo(7))
        val delete =
            assertThrows(DataAccessException::class.java) {
                p.bound { deleteFrom(DSL.table(DSL.name(SCHEMA, "audit_entry"))).execute() }
            }
        assertEquals("42501", p.db.tx.sqlStateOf(delete))
        val young =
            assertThrows(DataAccessException::class.java) {
                p.call(
                    PurgeCall(
                        p.yearsAgo(4),
                        "raw",
                        p
                            .stage()
                            .envelope.id.value,
                        "/gst/control-plane-svc",
                        "s",
                        "i",
                        "c",
                        "c",
                    ),
                )
            }
        assertEquals("22023", p.db.tx.sqlStateOf(young))
        assertEquals(1L, p.countAll("select count(*) from controlplane.audit_entry"))
    }

    fun refusals(): Stream<Arguments> =
        IMAGES
            .flatMap { image -> REFUSALS.keys.map { Arguments.of(image, it) } }
            .stream()

    /** The arguments of one `purge_audit_entries` call. */
    private data class PurgeCall(
        val cutOff: Instant,
        val reason: String,
        val entryId: String,
        val source: String,
        val subject: String,
        val instanceId: String,
        val correlationId: String,
        val causationId: String,
    ) {
        companion object {
            fun of(
                staged: StagedAuditEntry,
                cutOff: Instant,
            ) = PurgeCall(
                cutOff = cutOff,
                reason = checkNotNull(staged.entry.reason),
                entryId = staged.envelope.id.value,
                source = staged.envelope.source,
                subject = staged.envelope.subject,
                instanceId = staged.envelope.instanceId,
                correlationId = staged.envelope.correlation.value,
                causationId = staged.envelope.causation.value,
            )
        }
    }

    /**
     * The fixture `control-plane-svc` with the audit table and the purge function, on one container ([extraInit]: SQL
     * of this provisioning variant, run as the schema owner after the DDL).
     */
    private class Purge(
        image: String,
        extraInit: String = "",
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                image,
                "reset role;\ncreate schema $SCHEMA authorization outbox_owner;\n" +
                    "grant usage on schema $SCHEMA to $APP;\n" + AuditTestDb.bindingInit(SCHEMA, APP, SYS) +
                    "set role outbox_owner;\n" +
                    AuditSchema.auditEntryDdl(SCHEMA, APP) + "\n" +
                    AuditSchema.purgeDdl(SCHEMA, APP, SYS, "control-plane-svc") + "\n" + extraInit + "\n" +
                    // A second schema with its own key and the same functions: the look-alike of the forgery sample.
                    "reset role;\ncreate schema $OTHER authorization outbox_owner;\n" +
                    "grant usage on schema $OTHER to $APP;\n" +
                    AuditTestDb.bindingInit(OTHER, APP, SYS, fill = OTHER_FILL) +
                    "set role outbox_owner;\n" + AuditSchema.auditEntryDdl(OTHER, APP) + "\n" +
                    AuditSchema.purgeDdl(OTHER, APP, SYS, "control-plane-svc"),
            )
        val db = AuditTestDb(fixture, SignedBinding.keys())
        val tenants = db.tenants
        private val ownerDsl = db.dsl(db.ownerTx, mapOf("$SCHEMA.audit_entry" to TableClass.TENANT))
        private val seeded = mutableSetOf(PLATFORM)

        /** The time the rows are aged against, a minute behind the database's `now()`. */
        val now: Instant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)

        /**
         * Empties the table with the fixture's superuser: the schema owner is a member of the sys role, so its own
         * deletes are held to the legal floor by the restrictive policy and cannot clear a young row.
         */
        fun reset() = fixture.runAsAdmin("truncate $SCHEMA.audit_entry")

        fun at(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

        fun yearsAgo(years: Long): Instant = ZonedDateTime.ofInstant(now, ZoneOffset.UTC).minusYears(years).toInstant()

        /** The purge's own entry, staged as control-plane-svc would stage it. */
        fun stage(clock: Clock = Clock.systemUTC()): StagedAuditEntry =
            LocalAuditWriter(
                db.dsl(db.tx, emptyMap()),
                SCHEMA,
                "control-plane-svc",
                "control-plane-svc-1",
                clock,
            ).stage(
                AuditEntry("operator-9", AuditSchema.PURGE_ACTION, SCOPE, "idem-key-1"),
                EventContext(
                    tenant = TenantId.PLATFORM,
                    entity = EntityRef.PLATFORM,
                    docUuid = null,
                    transactionDate = null,
                    aggregateKey = "audit-purge",
                    aggregateVersion = null,
                    correlation = CorrelationId("corr-purge"),
                    causation = CausationId("cmd-purge"),
                ),
            )

        /** An existing entry of [tenant] recorded at [at], written as the schema owner under [tenant]'s binding. */
        fun seed(
            tenant: UUID,
            at: Instant,
            id: String = UUID.randomUUID().toString(),
        ) {
            seeded += tenant
            db.ownerInsert(
                ownerDsl,
                TenantId(tenant),
                SCHEMA,
                "audit_entry",
                mapOf(
                    "id" to UUID.fromString(id),
                    "tenant_id" to tenant,
                    "entity_ref" to "e",
                    "recorded_at" to at(at),
                    "source" to "/gst/control-plane-svc",
                    "subject" to "s",
                    "instance_id" to "i",
                    "correlation_id" to "c",
                    "causation_id" to "c",
                    "actor" to "operator-1",
                    "action" to "grantRole",
                    "scope" to "x",
                ),
            )
        }

        /** Calls the function as the runtime login, bound to the platform tenant (or [tenant]) and an actor. */
        fun call(
            call: PurgeCall,
            tenant: UUID? = null,
            unsetActor: Boolean = false,
        ): Long {
            seeded += tenant ?: PLATFORM
            val id = tenant?.let { TenantId(it) } ?: TenantId.PLATFORM
            // The runner never binds a blank actor; a value signed for the blank actor replaces its own, so the
            // function's check refuses.
            val signature = if (unsetActor) SignedBinding.value(SignedBinding.key(), id, "") else null
            return db.signed(id, "operator-9", signature = signature) { purge(call) }
        }

        /** Calls the function as in [call], by default on a runner whose key the schema does not hold (a forgery). */
        fun forgedCall(
            call: PurgeCall,
            schema: String = SCHEMA,
            runner: TransactionRunner = db.forgedTx,
        ): Long = db.signed(TenantId.PLATFORM, "operator-9", runner) { purge(call, schema) }

        private fun DSLContext.purge(
            call: PurgeCall,
            schema: String = SCHEMA,
        ): Long =
            select(
                DSL.function(
                    DSL.name(schema, "purge_audit_entries"),
                    Long::class.java,
                    DSL.`val`(at(call.cutOff)),
                    DSL.`val`(call.reason),
                    DSL.`val`(UUID.fromString(call.entryId)),
                    DSL.`val`(call.source),
                    DSL.`val`(call.subject),
                    DSL.`val`(call.instanceId),
                    DSL.`val`(call.correlationId),
                    DSL.`val`(call.causationId),
                ),
            ).fetchSingle().value1()

        /** Runs [block] as the runtime login under the platform tenant and a signed actor. */
        fun <T> bound(block: DSLContext.() -> T): T =
            db.signed(
                TenantId.PLATFORM,
                "operator-9",
                tables = mapOf("$SCHEMA.audit_entry" to TableClass.TENANT),
                block = block,
            )

        /** The rows of [sql] under the platform tenant's RLS binding. */
        fun platformRows(
            sql: String,
            vararg args: Any,
        ) = db.app.inTenant(TenantId.PLATFORM, "operator-9") { fetchRows(sql, *args) }

        /** The one-number [sql] summed over the RLS binding of every tenant this fixture touched. */
        fun countAll(
            sql: String,
            vararg args: Any,
        ): Long = db.count(sql, seeded, *args)

        override fun close() = db.close()
    }

    private companion object {
        const val SCHEMA = "controlplane"
        const val OTHER = "otherplane"
        const val PATH = "search_path=pg_catalog, controlplane, pg_temp"
        const val OTHER_FILL = 3
        const val SCOPE = "platform (control-plane-svc audit_entry)"
        const val DAY = 86_400L
        val PLATFORM: UUID = TenantId.PLATFORM.value
        val IMAGES = listOf("postgres:17", "postgres:18")
        val PRIVILEGE_CHECKS = setOf("actor unset", WRONG_TENANT)
        const val ALREADY_PRESENT = "entry id already present"
        const val WRONG_TENANT = "tenant not the platform tenant"
        const val OPEN_SYS_INSERT =
            "create policy audit_entry_test_open on $SCHEMA.audit_entry for insert to $SYS with check (true);"
        const val NO_SYS_INSERT = "revoke insert on $SCHEMA.audit_entry from $SYS;"

        /** Each check of the function, and the call that fails only that check. */
        val REFUSALS: Map<String, (Purge, PurgeCall) -> Long> =
            mapOf(
                "actor unset" to { p, call -> p.call(call, unsetActor = true) },
                WRONG_TENANT to { p, call -> p.call(call, tenant = UUID.randomUUID()) },
                "cut-off younger than the legal floor" to
                    { p, call -> p.call(call.copy(cutOff = p.yearsAgo(5).plusSeconds(DAY))) },
                "blank reason" to { p, call -> p.call(call.copy(reason = " ")) },
                "blank subject" to { p, call -> p.call(call.copy(subject = "")) },
                "blank instance" to { p, call -> p.call(call.copy(instanceId = " ")) },
                "blank correlation" to { p, call -> p.call(call.copy(correlationId = "")) },
                "blank causation" to { p, call -> p.call(call.copy(causationId = " ")) },
                "source of another service" to { p, call -> p.call(call.copy(source = "/gst/tenancy-svc")) },
                "entry id not a version-7 UUID" to
                    { p, call -> p.call(call.copy(entryId = UUID.randomUUID().toString())) },
                ALREADY_PRESENT to { p, call -> p.call(call) },
            )
    }
}
