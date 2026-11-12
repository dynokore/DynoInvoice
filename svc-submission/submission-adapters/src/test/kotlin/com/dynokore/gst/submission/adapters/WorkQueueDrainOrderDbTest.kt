package com.dynokore.gst.submission.adapters

import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * The drain order of the work queue (INV-AC-20563; INV-REQ-554; INV-T-20564). The scenario needs a database migrated
 * by every migration except the new one, so this class starts its own PostgreSQL (the shared [SubmissionDb] applies
 * all of them), queues R0 there, then applies the new migration and queues the rest through the real `JdbcWorkQueue`
 * of the pinned platform-libs, whose `assignDrainOrder` sets tier and rank.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkQueueDrainOrderDbTest {
    private val newFile = "V202611131500__work_queue_drain_order.sql"
    private val previous = "202611131400"
    private val tenant = UUID.randomUUID()

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var r0AfterMigration: Pair<Int, Int?>
    private lateinit var r0AfterSecondRun: Pair<Int, Int?>
    private lateinit var definitionBefore: String
    private lateinit var definitionAfter: String
    private var secondRunFailure: Throwable? = null
    private val claimOrder = mutableListOf<String>()

    private fun superuser(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway(target: String?) =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, SubmissionDb.MIGRATE, SubmissionDb.MIGRATE_PASSWORD)
            .schemas(SubmissionDb.SCHEMA)
            .defaultSchema(SubmissionDb.SCHEMA)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .also { if (target != null) it.target(target) }
            .load()

    private fun tierAndRank(partition: String): Pair<Int, Int?> =
        superuser().use { c ->
            val sql = "select drain_tier, drain_rank from submission.work_queue where partition_key = ?"
            c.prepareStatement(sql).use { st ->
                st.setString(1, partition)
                st.executeQuery().use { rs ->
                    check(rs.next()) { "no row for $partition" }
                    rs.getInt(1) to rs.getObject(2)?.let { rs.getInt(2) }
                }
            }
        }

    private fun definition(): String =
        superuser().use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select pg_get_functiondef('$CLAIM_DUE'::regprocedure)",
                    ).use {
                        it.next()
                        it.getString(1)
                    }
            }
        }

    @BeforeAll
    fun scenario() {
        postgres = startPostgres()
        // Every migration except the new one, then R0 (partition P0, enqueued first).
        flyway(previous).migrate()
        update(
            "insert into submission.work_queue (tenant_id, partition_key, lane, payload) " +
                "values ('$tenant', 'P0', 'LIVE', '{}')",
        )
        flyway(null).migrate()
        r0AfterMigration = tierAndRank("P0")
        DriverManager.getConnection(postgres.jdbcUrl, SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use {
            queueAndAssign(it)
        }
        definitionBefore = definition()
        secondRunFailure = runCatching { runNewMigrationText() }.exceptionOrNull()
        r0AfterSecondRun = tierAndRank("P0")
        definitionAfter = definition()
        claimAll()
    }

    private fun startPostgres(): PostgreSQLContainer<*> {
        val image = DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres")
        return PostgreSQLContainer(image)
            .withLabel("inv.agent", "c-inv-t-20564")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/roles.sql"), "$INIT/00-roles.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/provision.sql"), "$INIT/01-provision.sql")
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")
            .also { it.start() }
    }

    private fun update(sql: String) = superuser().use { c -> c.createStatement().use { it.execute(sql) } }

    private fun queueAndAssign(connection: Connection) {
        connection.autoCommit = false
        val dsl = DSL.using(connection, SQLDialect.POSTGRES)
        val transactions =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T {
                    SignedBindings.bind(connection, tenant)
                    return block().also { connection.commit() }
                }
            }
        val scope = TenantScope(fixedTenant(), transactions, connectionOf(connection), SignedBindings.ACTOR)
        val limiter = LaneRateLimiter(scope, dsl, SubmissionDb.SCHEMA, LaneLimits())
        val queue = JdbcWorkQueue(scope, dsl, SubmissionDb.SCHEMA, limiter, Clock.systemUTC())
        // P1b is the second item of partition P1, queued after P3.
        transactions.inTransaction {
            listOf("P1", "P2", "P3", "P1").forEach { queue.enqueue(QueueEntry(it, Lane.LIVE, "{}")) }
        }
        transactions.inTransaction {
            queue.assignDrainOrder("P1", 3, null)
            queue.assignDrainOrder("P2", 1, 4)
        }
        // assignDrainOrder works per partition, so it cannot give P1b a tier other than P1's: the later item is set
        // by the same columns directly (gap reported in the merge request).
        update(
            "update submission.work_queue set drain_tier = 1, drain_rank = 1 where partition_key = 'P1' and " +
                "seq = (select max(seq) from submission.work_queue where partition_key = 'P1')",
        )
    }

    private fun connectionOf(connection: Connection) =
        object : CurrentConnection {
            override fun require(): Connection = connection
        }

    private fun fixedTenant() =
        object : TenantContext {
            override fun current(): TenantId = TenantId(tenant)

            override fun currentActor(): String = SignedBindings.ACTOR

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = error("not used")
        }

    private fun partitionsBySeq(): Map<Long, String> =
        superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select seq, partition_key from submission.work_queue order by seq").use { rs ->
                    generateSequence { if (rs.next()) rs.getLong(1) to rs.getString(2) else null }.toMap()
                }
            }
        }

    private fun claimOne(c: Connection): Long? =
        c.createStatement().use { st -> st.executeQuery(CLAIM).use { rs -> if (rs.next()) rs.getLong(1) else null } }

    private fun claimAll() {
        val names = partitionsBySeq()
        val p1b = names.filterValues { it == "P1" }.keys.max()
        DriverManager.getConnection(postgres.jdbcUrl, SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { c ->
            generateSequence { claimOne(c) }.forEach { claimOrder += if (it == p1b) "P1b" else checkNotNull(names[it]) }
        }
    }

    private fun runNewMigrationText() {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$newFile")) { "$newFile missing" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
        DriverManager.getConnection(postgres.jdbcUrl, SubmissionDb.MIGRATE, SubmissionDb.MIGRATE_PASSWORD).use { c ->
            c.autoCommit = true
            c.createStatement().use { it.execute(text) }
        }
    }

    @AfterAll
    fun stop() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `INV-AC-20563 a claims by tier, then rank, then enqueue position, in one lane`() {
        definitionAfter.contains(ORDER_BY) shouldBe true
        claimOrder.first() shouldBe "P2"
        claimOrder.filter { it in setOf("P0", "P1", "P3") } shouldBe listOf("P0", "P1", "P3")
        superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select count(distinct lane) from submission.work_queue").use {
                    it.next()
                    it.getInt(1) shouldBe 1
                }
            }
        }
    }

    @Test
    fun `INV-AC-20563 b an item queued before the migration reads tier 3 and rank null`() {
        r0AfterMigration shouldBe (3 to null)
    }

    @Test
    fun `INV-AC-20563 c the later item of a partition is never returned before the earlier one`() {
        val p1 = claimOrder.indexOf("P1")
        val p1b = claimOrder.indexOf("P1b")
        (p1b == -1 || p1 in 0 until p1b) shouldBe true
    }

    @Test
    fun `INV-AC-20563 d the migration text run a second time changes nothing`() {
        secondRunFailure shouldBe null
        r0AfterSecondRun shouldBe r0AfterMigration
        definitionAfter shouldBe definitionBefore
        definitionAfter.contains(ORDER_BY) shouldBe true
    }

    @Test
    fun `claim_due is owned by the sys role and executable by the runtime role`() {
        superuser().use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select pg_get_userbyid(proowner), " +
                            "has_function_privilege('submission_runtime', oid, 'execute') " +
                            "from pg_proc where oid = '$CLAIM_DUE'::regprocedure",
                    ).use {
                        it.next()
                        it.getString(1) shouldBe SubmissionDb.SYS
                        it.getBoolean(2) shouldBe true
                    }
            }
        }
    }

    private companion object {
        const val IMAGE = "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336"
        const val CLAIM_DUE = "submission.claim_due(text, text, timestamptz, timestamptz)"
        const val ORDER_BY = "q.drain_tier, q.drain_rank nulls last, q.seq"
        const val INIT = "/docker-entrypoint-initdb.d"
        const val CLAIM = "select claimed_seq from submission.claim_due('LIVE', null, now(), now() - interval '1 hour')"
    }
}
