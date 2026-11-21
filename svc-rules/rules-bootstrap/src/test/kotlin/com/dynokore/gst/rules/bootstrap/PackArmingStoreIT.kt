package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.PackReadinessAcknowledgedHandler
import com.dynokore.gst.rules.adapters.events.PackReadinessEvents
import com.dynokore.gst.rules.adapters.persistence.JooqPackArmingStore
import com.dynokore.gst.rules.application.ActivationReview
import com.dynokore.gst.rules.application.ArmingWrite
import com.dynokore.gst.rules.application.DeltaAcknowledgement
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.StoredReadiness
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.rulepackkernel.fence.ReadinessFailure
import com.dynokore.platform.rulepackkernel.fence.ReadinessOutcome
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val PACK_TYPE = "SCHEMATRON"
private const val PLATFORM_ACTOR = "svc:rules-svc/op:test"
private val TICK_1 = Instant.parse("2026-11-10T01:00:00Z")
private val TICK_2 = Instant.parse("2026-11-10T02:00:00Z")
private val TICK_3 = Instant.parse("2026-11-10T03:00:00Z")

/**
 * INV-T-22942 (INV-REQ-952): the arming tables, functions, stores and the readiness consumer over real PostgreSQL, the
 * service on its runtime login. The subject is the function results and the full content of every table of the schema
 * `rules`, read before and after each call (as the superuser, which row-level security does not hide) and compared by
 * equality.
 */
@Suppress("LongMethod", "LongParameterList") // one integration case per assertion group, constructor-injected beans
@SpringBootTest(classes = [RulesApplication::class])
class PackArmingStoreIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val platform: PlatformTenantBinding,
    @Autowired private val db: DSLContext,
    @Autowired private val emitter: PackEventEmitter,
    @Autowired private val scope: TenantScope,
    @Autowired private val sink: RebuildRequiredSink,
) {
    private val pg = PostgresRules.pg
    private val runtime = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val store get() = JooqPackArmingStore(db, platform, tx, emitter)

    private fun superuser(): Connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    private fun <T> superuser(block: (Connection) -> T): T = superuser().use(block)

    private fun version() = "t" + UUID.randomUUID().toString().take(8)

    private fun newTenant() = TenantId(UUID.randomUUID())

    /** Every table of the schema `rules` with its rows as text, rows in text order. */
    private fun snapshot(excluding: Set<String> = emptySet()): Map<String, List<String>> =
        superuser { c ->
            val names = mutableListOf<String>()
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select table_name from information_schema.tables " +
                            "where table_schema = 'rules' and table_type = 'BASE TABLE' " +
                            "and table_name <> 'lane_rate_window' order by 1",
                    ).use { while (it.next()) names += it.getString(1) }
            }
            names.filter { it !in excluding }.associateWith { name ->
                c.createStatement().use { st ->
                    st.executeQuery("select to_jsonb(t)::text from rules.\"$name\" t order by 1").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        }

    private fun seedPack(
        version: String,
        approve: Boolean = true,
    ) {
        runtime.inTenant(TenantId.PLATFORM) {
            fetchValue(
                "select rules.register_platform_pack(?::uuid, 'SCHEMATRON-pack', ?, ?, 'STAGED', '2027-01-01'::date, " +
                    "null, '1.0.0', null)",
                UUID.randomUUID().toString(),
                PACK_TYPE,
                version,
            )
            if (approve) fetchValue("select rules.approve_platform_pack(?, ?)", PACK_TYPE, version)
        }
    }

    private fun delta(
        who: TenantId,
        version: String,
        state: String,
        completedAt: Instant? = null,
        type: String = PACK_TYPE,
    ) = superuser { c ->
        c
            .prepareStatement(
                "insert into rules.dry_run_delta (id, tenant_id, pack_type, pack_version, state, started_by, " +
                    "completed_at, changed_count, unavailable_reason) values (?::uuid, ?::uuid, ?, ?, ?, 'u-dry', " +
                    "?::timestamptz, ?, ?)",
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, who.toString())
                ps.setString(3, type)
                ps.setString(4, version)
                ps.setString(5, state)
                ps.setString(6, completedAt?.toString())
                if (state == "COMPLETE") ps.setInt(7, 0) else ps.setNull(7, java.sql.Types.INTEGER)
                ps.setString(8, if (state == "SAMPLE_UNAVAILABLE") "none" else null)
                ps.executeUpdate()
            }
    }

    private fun acknowledge(
        who: TenantId,
        version: String,
        by: String = "u-ack",
        at: Instant = TICK_1,
    ) = tenants.bind(who, by) {
        tx.inTransaction { store.recordOnce(DeltaAcknowledgement(who.toString(), PACK_TYPE, version, by, at)) }
    }

    private fun sqlState(e: Throwable): String? {
        var t: Throwable? = e
        while (t != null) {
            if (t is SQLException && t.sqlState != null) return t.sqlState
            t = t.cause
        }
        return null
    }

    private fun scalar(
        sql: String,
        vararg binds: String,
    ): String? =
        superuser { c ->
            c.prepareStatement(sql).use { ps ->
                binds.forEachIndexed { i, b -> ps.setString(i + 1, b) }
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun status(version: String) =
        scalar("select status from rules.pack_registry where pack_type = ? and pack_version = ?", PACK_TYPE, version)

    private fun reviews(version: String) =
        scalar(
            "select coalesce(string_agg(tenant_id::text || ':' || reviewed || ':' || decided_on, ',' " +
                "order by tenant_id), '') " +
                "from rules.pack_activation_review where pack_type = ? and pack_version = ?",
            PACK_TYPE,
            version,
        )

    // The rows of the platform-class event, compared field by field: type, topic, platform tenant and platform entity.
    private fun outboxRows(version: String) =
        scalar(
            "select count(*)::text from rules.outbox where type = 'com.dynokore.gst.platform.RulePackActivated.v1' " +
                "and topic = 'gst.platform.rule-pack-activated.v1' and tenant_id = ?::uuid and entity_ref = ? " +
                "and subject = ?",
            TenantId.PLATFORM.value.toString(),
            EntityRef.PLATFORM.toString(),
            "$PACK_TYPE@$version",
        )

    private fun domainTypeOutboxRows() =
        scalar("select count(*)::text from rules.outbox where type = 'com.dynokore.gst.rules.RulePackActivated.v1'")

    // (a) the tenant function

    @Test
    fun `INV-AC-22940 the tenant function lists one row per tenant with a COMPLETE delta and nothing else`() {
        val v = version()
        val withTwo = newTenant()
        val plain = newTenant()
        val running = newTenant()
        val unavailable = newTenant()
        val otherVersion = newTenant()
        delta(withTwo, v, "COMPLETE", TICK_1)
        delta(withTwo, v, "COMPLETE", TICK_3)
        delta(withTwo, v, "RUNNING")
        delta(plain, v, "COMPLETE", TICK_2)
        delta(running, v, "RUNNING")
        delta(unavailable, v, "SAMPLE_UNAVAILABLE")
        delta(otherVersion, version(), "COMPLETE", TICK_1)
        acknowledge(withTwo, v)
        val before = snapshot()
        val listed = store.tenantsWithCompleteDelta(PACK_TYPE, v).associateBy { it.tenantId }
        listed.keys shouldBe setOf(withTwo.toString(), plain.toString())
        listed.getValue(withTwo.toString()).completedAt shouldBe TICK_3
        listed.getValue(withTwo.toString()).acknowledged shouldBe true
        listed.getValue(plain.toString()).completedAt shouldBe TICK_2
        listed.getValue(plain.toString()).acknowledged shouldBe false
        snapshot() shouldBe before
        store.tenantsWithCompleteDelta(PACK_TYPE, version()) shouldBe emptyList()
        // a tenant binding is refused: no tenant ID of another tenant leaves the function
        val refused =
            shouldThrow<Exception> {
                runtime.inTenant(withTwo) {
                    fetchRows("select * from rules.tenants_with_delta(?, ?)", PACK_TYPE, v)
                }
            }
        sqlState(refused) shouldBe "42501"
        snapshot() shouldBe before
    }

    // (b) row-level policies

    @Test
    fun `INV-AC-22940 a tenant binding reads its own acknowledgement and review rows and no row of the other tenant`() {
        val v = version()
        val one = newTenant()
        val two = newTenant()
        seedPack(v)
        delta(one, v, "COMPLETE", TICK_1)
        delta(two, v, "COMPLETE", TICK_1)
        acknowledge(one, v, "u-one")
        acknowledge(two, v, "u-two")
        // the review rows are written through the definer function under the platform binding
        runtime.inTenant(TenantId.PLATFORM, PLATFORM_ACTOR) {
            fetchValue(
                "select rules.arm_platform_pack(?, ?, array[?::uuid, ?::uuid], array[true, false], " +
                    "array['2026-12-01'::date, '2026-12-01'::date])",
                PACK_TYPE,
                v,
                one.toString(),
                two.toString(),
            )
        }

        fun seen(
            who: TenantId,
            table: String,
        ) = runtime.inTenant(who) {
            fetchRows("select tenant_id::text as t from rules.$table where pack_version = ?", v)
        }
        seen(one, "pack_delta_ack") shouldBe listOf(mapOf("t" to one.toString()))
        seen(two, "pack_delta_ack") shouldBe listOf(mapOf("t" to two.toString()))
        seen(one, "pack_activation_review") shouldBe listOf(mapOf("t" to one.toString()))
        seen(two, "pack_activation_review") shouldBe listOf(mapOf("t" to two.toString()))
        seen(newTenant(), "pack_delta_ack") shouldBe emptyList()
        seen(newTenant(), "pack_activation_review") shouldBe emptyList()
        // the tenant-bound store read returns the caller's own row only
        tenants.bind(one, "u-one") {
            tx.inTransaction {
                store.find(one.toString(), PACK_TYPE, v)?.acknowledgedBy shouldBe "u-one"
                store.find(two.toString(), PACK_TYPE, v) shouldBe null
                store.completedAt(two.toString(), PACK_TYPE, v) shouldBe null
                store.completedAt(one.toString(), PACK_TYPE, v) shouldBe TICK_1
            }
        }
    }

    // (c) the write path and the privileges

    @Test
    fun `INV-AC-22940 a direct insert of a review row is refused and the writing function refuses a tenant binding`() {
        val v = version()
        val one = newTenant()
        seedPack(v)
        val before = snapshot()
        val direct =
            shouldThrow<Exception> {
                runtime.inTenant(one) {
                    fetchValue(
                        "insert into rules.pack_activation_review " +
                            "(tenant_id, pack_type, pack_version, reviewed, decided_on) " +
                            "values (?::uuid, ?, ?, true, current_date) returning 1",
                        one.toString(),
                        PACK_TYPE,
                        v,
                    )
                }
            }
        sqlState(direct) shouldBe "42501"
        val viaFunction =
            shouldThrow<Exception> {
                runtime.inTenant(one) {
                    fetchValue(
                        "select rules.arm_platform_pack(?, ?, array[?::uuid], array[true], array[current_date])",
                        PACK_TYPE,
                        v,
                        one.toString(),
                    )
                }
            }
        sqlState(viaFunction) shouldBe "42501"
        snapshot() shouldBe before
        status(v) shouldBe "APPROVED"
        // both functions: SECURITY DEFINER, owned by rules_sys, executable by the runtime login and not by PUBLIC
        val acl =
            "select p.prosecdef::text || ':' || pg_get_userbyid(p.proowner) || ':' || " +
                "exists (select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a " +
                "where a.grantee = 0 and a.privilege_type = 'EXECUTE')::text || ':' || " +
                "exists (select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a " +
                "where a.grantee = 'rules_runtime'::regrole and a.privilege_type = 'EXECUTE')::text || ':' || " +
                "coalesce(array_to_string(p.proconfig, ','), '') from pg_proc p " +
                "join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'rules' and p.proname = ?"
        for (name in listOf("tenants_with_delta", "arm_platform_pack")) {
            scalar(acl, name) shouldBe "true:rules_sys:false:true:search_path=pg_catalog, rules, pg_temp"
        }
    }

    // (d) the arming write

    @Test
    fun `INV-AC-22940 the arming write sets ARMED and stores the review rows and the outbox row together or none`() {
        val v = version()
        val one = newTenant()
        val two = newTenant()
        seedPack(v)
        val write =
            ArmingWrite(
                PACK_TYPE,
                v,
                LocalDate.parse("2027-01-01"),
                listOf(
                    ActivationReview(one.toString(), true, LocalDate.parse("2026-12-01")),
                    ActivationReview(two.toString(), false, LocalDate.parse("2026-12-01")),
                ),
            )
        val before = snapshot()
        val failing =
            object : PackEventEmitter {
                override fun emit(
                    events: List<PackEvent>,
                    context: EventContext,
                ) {
                    error("the outbox insert fails")
                }
            }
        shouldThrow<IllegalStateException> {
            JooqPackArmingStore(db, platform, tx, failing).arm(write)
        }
        snapshot() shouldBe before
        status(v) shouldBe "APPROVED"
        reviews(v) shouldBe ""
        outboxRows(v) shouldBe "0"
        store.arm(write)
        status(v) shouldBe "ARMED"
        reviews(v) shouldBe
            listOf(one, two).sortedBy { it.toString() }.joinToString(",") {
                "$it:${it == one}:2026-12-01"
            }
        outboxRows(v) shouldBe "1"
        domainTypeOutboxRows() shouldBe "0"
        store.find(PACK_TYPE, v)?.status shouldBe PackStatus.ARMED
        // an armed pack is not armed a second time, and nothing changes
        val armed = snapshot()
        shouldThrow<Exception> { store.arm(write) }
        snapshot() shouldBe armed
    }

    // (e) the acknowledgement write

    @Test
    fun `INV-AC-22940 the acknowledgement write twice leaves the first row unchanged`() {
        val v = version()
        val one = newTenant()
        acknowledge(one, v, "u-first", TICK_1)
        val before = snapshot()
        acknowledge(one, v, "u-second", TICK_3)
        snapshot() shouldBe before
        val row = tenants.bind(one, "u-first") { tx.inTransaction { store.find(one.toString(), PACK_TYPE, v) } }
        row?.acknowledgedBy shouldBe "u-first"
        row?.acknowledgedAt shouldBe TICK_1
        // the acting user ID is never blank and never holds a control character
        shouldThrow<Exception> { acknowledge(newTenant(), v, "u-bad\n") }
        shouldThrow<Exception> { acknowledge(newTenant(), v, " ") }
        snapshot() shouldBe before
    }

    // (f) the readiness answers

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private inner class Harness {
        val log = Log()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription("rules-svc-" + UUID.randomUUID(), PackReadinessEvents.ACKNOWLEDGED_TOPIC, "rules"),
                EventCatalogue(listOf(PackReadinessEvents.acknowledgedEntry())),
                log,
                scope,
                db,
                PackReadinessAcknowledgedHandler(store),
                sink,
                Clock.systemUTC(),
            )

        fun answer(
            version: String,
            instance: String?,
            outcome: String,
            at: Instant,
            reason: String? = null,
        ) {
            val data =
                buildString {
                    append("{\"packIdentifier\":\"$PACK_TYPE\",\"version\":\"$version\",")
                    if (instance != null) append("\"instance\":\"$instance\",")
                    append("\"outcome\":\"$outcome\"")
                    if (reason != null) append(",\"failureReason\":\"$reason\"")
                    append("}")
                }
            log.add(
                EnvelopeJson().write(
                    Envelope(
                        id = EventId(UUID.randomUUID().toString()),
                        source = "/gst/notification-svc",
                        type = PackReadinessEvents.ACKNOWLEDGED,
                        subject = "$PACK_TYPE@$version",
                        time = at,
                        tenant = TenantId.PLATFORM,
                        entity = EntityRef.PLATFORM,
                        docUuid = null,
                        transactionDate = null,
                        docSequence = null,
                        instanceId = instance ?: "notification-svc-0",
                        correlation = CorrelationId("corr-1"),
                        causation = CausationId("cause-1"),
                        dataJson = data,
                    ),
                ),
            )
        }
    }

    private fun answers(version: String) = store.answers(PACK_TYPE, version).associateBy { it.instance }

    private val failed = ReadinessOutcome.Failed(ReadinessFailure.COMPILE_FAILED, "COMPILE_FAILED")

    @Test
    fun `INV-AC-22940 the latest readiness answer of an instance wins and a second instance is separate`() {
        val v = version()
        val h = Harness()
        h.answer(v, "i-1", "LOADED", TICK_1)
        h.consumer.pollOnce().applied shouldBe 1
        answers(v)["i-1"] shouldBe StoredReadiness("i-1", ReadinessOutcome.Loaded)
        h.answer(v, "i-1", "FAILED", TICK_2, "COMPILE_FAILED")
        h.consumer.pollOnce().applied shouldBe 1
        answers(v)["i-1"] shouldBe StoredReadiness("i-1", failed)
        // an older Loaded changes nothing
        val before = scalar("select to_jsonb(t)::text from rules.pack_readiness_ack t where pack_version = ?", v)
        h.answer(v, "i-1", "LOADED", TICK_1)
        h.consumer.pollOnce().ignored shouldBe 1
        answers(v)["i-1"] shouldBe StoredReadiness("i-1", failed)
        scalar("select to_jsonb(t)::text from rules.pack_readiness_ack t where pack_version = ?", v) shouldBe before
        // a second instance does not change the first
        h.answer(v, "i-2", "LOADED", TICK_3)
        h.consumer.pollOnce().applied shouldBe 1
        answers(v)["i-1"] shouldBe StoredReadiness("i-1", failed)
        answers(v)["i-2"] shouldBe StoredReadiness("i-2", ReadinessOutcome.Loaded)
        answers(v).size shouldBe 2
    }

    @Test
    fun `INV-AC-22940 an unknown failure reason reads as VERIFICATION_FAILED and the text is stored verbatim`() {
        val v = version()
        val h = Harness()
        h.answer(v, "i-1", "FAILED", TICK_1, "unknown reason")
        h.consumer.pollOnce().applied shouldBe 1
        answers(v)["i-1"] shouldBe
            StoredReadiness("i-1", ReadinessOutcome.Failed(ReadinessFailure.VERIFICATION_FAILED, "unknown reason"))
        scalar("select failure_reason from rules.pack_readiness_ack where pack_version = ?", v) shouldBe
            "unknown reason"
        // a blank and a very long reason are stored as sent, never refused
        h.answer(v, "i-2", "FAILED", TICK_1, "   ")
        h.answer(v, "i-3", "FAILED", TICK_1, "x".repeat(5000))
        h.consumer.pollOnce().applied shouldBe 2
        scalar(
            "select failure_reason from rules.pack_readiness_ack where pack_version = ? and instance = 'i-2'",
            v,
        ) shouldBe
            "   "
        scalar(
            "select length(failure_reason)::text from rules.pack_readiness_ack " +
                "where pack_version = ? and instance = 'i-3'",
            v,
        ) shouldBe "5000"
    }

    @Test
    fun `INV-AC-22940 an answer of the same time as the stored one changes nothing and keeps the stored row`() {
        val v = version()
        val h = Harness()
        h.answer(v, "i-1", "LOADED", TICK_1)
        h.consumer.pollOnce().applied shouldBe 1
        val before = scalar("select to_jsonb(t)::text from rules.pack_readiness_ack t where pack_version = ?", v)
        h.answer(v, "i-1", "FAILED", TICK_1, "COMPILE_FAILED")
        h.consumer.pollOnce().ignored shouldBe 1
        answers(v)["i-1"] shouldBe StoredReadiness("i-1", ReadinessOutcome.Loaded)
        scalar("select to_jsonb(t)::text from rules.pack_readiness_ack t where pack_version = ?", v) shouldBe before
    }

    @Test
    fun `INV-AC-22940 a readiness payload without the instance field stores nothing`() {
        val v = version()
        val h = Harness()
        val keep = setOf("inbox", "outbox", "dead_letter", "consumer_offset")
        val before = snapshot().filterKeys { name -> keep.none { name.contains(it) } }
        h.answer(v, null, "LOADED", TICK_1)
        h.consumer.pollOnce().applied shouldBe 0
        answers(v) shouldBe emptyMap()
        snapshot().filterKeys { name -> keep.none { name.contains(it) } } shouldBe before
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)
    }
}
