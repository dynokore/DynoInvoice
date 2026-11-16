package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The object store of the context: remembers what is written, and can be held or made to fail (a test seam). */
class GateStore : ObjectStore {
    @Volatile var gate: CountDownLatch? = null

    @Volatile var failPuts: Boolean = false
    val objects = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override fun put(
        key: ObjectKey,
        bytes: ByteArray,
        retainUntil: Instant,
    ): ObjectRef {
        gate?.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)
        check(!failPuts) { "the object store is made to fail" }
        objects[key.value] = bytes
        return ObjectRef(key, retainUntil)
    }

    override fun get(key: ObjectKey): ByteArray = objects.getValue(key.value)

    private companion object {
        const val HOLD_LIMIT_SECONDS = 30L
    }
}

/** Active only while [PartitionDeletionHttpTest] sets [GateStoreBeans.PROPERTY]: the scan of other tests skips it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(GateStoreBeans.PROPERTY, havingValue = "true")
class GateStoreBeans {
    @Bean
    @Primary
    fun gateStore(): GateStore = store

    companion object {
        const val PROPERTY = "evidence.test.gate-store"
        val store = GateStore()
    }
}

/**
 * INV-AC-22964 over HTTP: `deleteAtRetentionEnd` and `getPartitionDeletion` in the service as TPL-18 composes it, over
 * the real PostgreSQL. The caller of every request is the end user [STARTER] or [OTHER] of a signed token; the
 * permission `retention.delete.request` is held by the replica grant of [STARTER] only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass", "TooManyFunctions") // one HTTP test class for the two operations of one task
class PartitionDeletionHttpTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService
    private val store = GateStoreBeans.store
    private val tables = RetentionPartitionFixture.CHAIN_TABLES
    private val tenants = mutableListOf<UUID>()

    @BeforeAll
    fun start() {
        System.setProperty(GateStoreBeans.PROPERTY, "true")
        System.setProperty(LOCK_PROPERTY, LOCK_PERIOD)
        dir = Files.createTempDirectory("pdh")
        service = RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, GateStoreBeans::class.java))
    }

    @AfterAll
    fun stop() {
        service.close()
        System.clearProperty(GateStoreBeans.PROPERTY)
        System.clearProperty(LOCK_PROPERTY)
        dir.toFile().deleteRecursively()
    }

    @BeforeEach
    fun reset() {
        store.gate = null
        store.failPuts = false
        store.objects.clear()
    }

    /** The detached tables of a unit are standalone tables until dropped: the shared schema is left as found. */
    @AfterEach
    fun dropDetachedTables() {
        EvidenceDb.superuser().use { c ->
            tenants.forEach { tenant ->
                val statements =
                    c
                        .prepareStatement(
                            "select 'drop table evidence.' || m.partition_table " +
                                "from evidence.retention_partition_member m " +
                                "join pg_class t on t.relname = m.partition_table " +
                                "and t.relnamespace = 'evidence'::regnamespace " +
                                "where m.tenant_id = ? and not t.relispartition",
                        ).use { st ->
                            st.setObject(1, tenant)
                            st.executeQuery().use { rs ->
                                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                            }
                        }
                c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            }
        }
        tenants.clear()
    }

    private class Fixture(
        val tenant: UUID,
        val partitionId: String,
    )

    private fun sql(
        text: String,
        vararg args: Any?,
    ): List<List<String?>> =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(text).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                if (st.execute()) {
                    st.resultSet.use { rs ->
                        val n = rs.metaData.columnCount
                        generateSequence { if (rs.next()) (1..n).map { rs.getString(it) } else null }.toList()
                    }
                } else {
                    emptyList()
                }
            }
        }

    /** A unit with one row in each chain table; the notice window closed and two approvals unless left out. */
    private fun fixture(
        period: String = "2010-Q1",
        notice: Boolean = true,
        hold: Boolean = false,
        grant: Boolean = true,
        gap: Gap? = null,
    ): Fixture {
        val tenant = UUID.randomUUID()
        tenants += tenant
        EvidenceDb.superuser().use { c ->
            RetentionPartitionFixture.ensureRetentionPartitions(c, tenant, ENTITY, period)
        }
        tables.forEach {
            sql(
                "insert into evidence.$it (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, " +
                    "content_hash, previous_hash, recorded_at) " +
                    "values (gen_random_uuid(), ?, ?, ?, 1, 'X', ?, ?, null, now())",
                tenant,
                ENTITY,
                period,
                "c-$it".toByteArray(),
                "hash-$it",
            )
        }
        val id =
            checkNotNull(
                sql("select partition_id from evidence.retention_partition where tenant_id = ?", tenant)
                    .single()
                    .single(),
            )
        authority(tenant, id, period, Authority(notice, hold, gap == Gap.WINDOW_OPEN, gap != Gap.NO_APPROVALS))
        seedStructure(TenantId(tenant))
        if (grant) {
            service.grants.grant(
                TenantId(tenant),
                STARTER,
                service.grants.role(REQUEST_ROLE, GrantScope.TENANT, tenant.toString()),
            )
        }
        return Fixture(tenant, id)
    }

    private fun authority(
        tenant: UUID,
        id: String,
        period: String,
        authority: Authority,
    ) {
        if (authority.notice) {
            val (notified, closes) =
                if (authority.windowOpen) {
                    "now() - interval '20 days'" to "now() + interval '10 days'"
                } else {
                    "now() - interval '90 days'" to "now() - interval '60 days'"
                }
            sql(
                "insert into evidence.retention_notice values (?, ?, $notified, 30, $closes)",
                tenant,
                id,
            )
        }
        listOf("u-approver-1", "u-approver-2").filter { authority.approvals }.forEach {
            sql(
                "insert into evidence.retention_approval values (?, ?, ?, 'retention.delete.approve', now())",
                tenant,
                id,
                it,
            )
        }
        if (authority.hold) {
            sql(
                "insert into evidence.legal_hold " +
                    "(tenant_id, entity_ref, period_ref, reason, status, placed_at, placed_by) " +
                    "values (?, ?, ?, 'audit', 'ACTIVE', now(), 'u-hold')",
                tenant,
                ENTITY,
                period,
            )
        }
    }

    private enum class Gap { WINDOW_OPEN, NO_APPROVALS }

    private data class Authority(
        val notice: Boolean,
        val hold: Boolean,
        val windowOpen: Boolean,
        val approvals: Boolean,
    )

    private fun seedStructure(tenant: TenantId) {
        val node =
            StructureNode(
                StructureNodeKind.ENTITY,
                null,
                retired = false,
                name = "entity",
                code = null,
                profile = emptyList(),
                structureVersion = 1,
            )
        service
            .bean(StructureReplica::class.java)
            .accept(OrganisationStructureChanged(tenant, ENTITY, StructureChange.CREATED, node))
    }

    private fun rows(f: Fixture): Int =
        tables.sumOf {
            sql("select count(*) from evidence.$it where tenant_id = ?", f.tenant).single().single()!!.toInt()
        }

    private fun registryState(f: Fixture): String =
        sql("select state from evidence.retention_partition where partition_id = ?", f.partitionId).single().single()!!

    private fun operations(f: Fixture): List<List<String?>> =
        sql(
            "select state, starter_user_id, error_code, (completed_at is not null)::text " +
                "from evidence.partition_deletion_operation where tenant_id = ? order by started_at",
            f.tenant,
        )

    private fun call(
        method: String,
        f: Fixture,
        suffix: String,
        user: String,
    ): Pair<HttpResponse<String>, JsonNode> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$PATH${f.partitionId}/$suffix"))
                .header("Authorization", "Bearer ${TestIdentity.token(user, f.tenant)}")
                .header("X-Workload-Token", "token-console-bff")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response to mapper.readTree(response.body())
    }

    private val port: String
        get() = service.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")!!

    private fun delete(
        f: Fixture,
        user: String = STARTER,
    ) = call("POST", f, "deletions", user)

    private fun read(
        f: Fixture,
        user: String = STARTER,
    ) = call("GET", f, "deletion", user)

    /** Equal after the per-request members (correlation id, instance path) are removed. */
    private fun sameResponse(
        a: Pair<HttpResponse<String>, JsonNode>,
        b: Pair<HttpResponse<String>, JsonNode>,
    ): Boolean {
        fun bare(n: JsonNode): JsonNode {
            val copy = n.deepCopy() as tools.jackson.databind.node.ObjectNode
            PER_REQUEST.forEach { copy.remove(it) }
            return copy
        }
        return a.status() == b.status() && bare(a.second) == bare(b.second)
    }

    private fun Pair<HttpResponse<String>, JsonNode>.status() = first.statusCode()

    private fun Pair<HttpResponse<String>, JsonNode>.code() = second.get("code")?.asString()

    private fun Pair<HttpResponse<String>, JsonNode>.detail() = second.get("detail")?.asString()

    /** A deletion that ends DELETED: a 200, or a 202 whose operation then ends SUCCEEDED (a loaded machine). */
    private fun deleted(f: Fixture) {
        val answer = delete(f)
        if (answer.status() != 200) {
            answer.status() shouldBe 202
            awaitState(f, "SUCCEEDED")
        }
    }

    private fun awaitState(
        f: Fixture,
        state: String,
    ) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (operations(f).lastOrNull()?.firstOrNull() != state) {
            check(System.nanoTime() < limit) { "the operation did not reach $state: ${operations(f)}" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    @Test
    fun `INV-AC-22964 no hold gives 200 DELETED the rows are gone and one audit entry holds the tail hashes`() {
        val f = fixture()
        rows(f) shouldBe tables.size
        val answer = delete(f)
        if (answer.status() == 200) {
            answer.second.get("status").asString() shouldBe "DELETED"
            (answer.second.get("deletedAt") != null) shouldBe true
        } else {
            // a machine so loaded that the deletion outlasts the contract timeout answers 202: the status follows
            answer.status() shouldBe 202
            awaitState(f, "SUCCEEDED")
        }
        rows(f) shouldBe 0
        registryState(f) shouldBe "DROPPED"
        val audits =
            sql(
                "select data->>'actor', data::text from evidence.outbox where tenant_id = ? " +
                    "and type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1'",
                f.tenant,
            )
        audits.size shouldBe 1
        audits.single()[0] shouldBe STARTER
        tables.forEach { audits.single()[1]!! shouldContain "$it=hash-$it" }
        operations(f) shouldBe listOf(listOf("SUCCEEDED", STARTER, null, "true"))
    }

    @Test
    fun `INV-AC-22964 the starter reads SUCCEEDED with the result and another principal gets 404 SRT-005`() {
        val f = fixture()
        deleted(f)
        val own = read(f)
        own.status() shouldBe 200
        own.second.get("state").asString() shouldBe "SUCCEEDED"
        own.second
            .get("result")
            .get("status")
            .asString() shouldBe "DELETED"
        own.second
            .get("result")
            .get("partitionId")
            .asString() shouldBe f.partitionId
        val other = read(f, OTHER)
        other.status() shouldBe 404
        other.code() shouldBe "SRT-005"
        other.detail() shouldBe null
        // a read of a partition nobody started deleting is not found, and the response is the same one
        val unknown = read(fixture())
        unknown.status() shouldBe 404
        sameResponse(other, unknown) shouldBe true
    }

    @Test
    fun `INV-AC-22964 the starter id of the operation is the calling user id and no role name`() {
        val f = fixture()
        deleted(f)
        operations(f).single()[1] shouldBe STARTER
        (operations(f).single()[1] == REQUEST_ROLE) shouldBe false
    }

    @Test
    fun `INV-AC-22964 an active hold gives 409 and the rows remain`() {
        val f = fixture(hold = true)
        val answer = delete(f)
        answer.status() shouldBe 409
        answer.code() shouldBe "EVD-004"
        answer.detail() shouldBe null
        rows(f) shouldBe tables.size
        registryState(f) shouldBe "ATTACHED"
        operations(f).single().take(3) shouldBe listOf("FAILED", STARTER, "EVD-004")
    }

    @Test
    fun `INV-AC-22964 a partition with no notice row gives 409 and the rows remain`() {
        val f = fixture(notice = false)
        val answer = delete(f)
        answer.status() shouldBe 409
        answer.code() shouldBe "EVD-005"
        answer.detail() shouldBe null
        rows(f) shouldBe tables.size
    }

    @Test
    fun `INV-AC-22964 a partition whose retention end is later than now gives 409 and the rows remain`() {
        val f = fixture(period = "2099-Q1")
        val answer = delete(f)
        answer.status() shouldBe 409
        answer.code() shouldBe "EVD-003"
        answer.detail() shouldBe null
        rows(f) shouldBe tables.size
    }

    @Test
    fun `INV-AC-22964 the caller without the key gives 403 SRT-004 and nothing changes`() {
        val f = fixture()
        val answer = delete(f, OTHER)
        answer.status() shouldBe 403
        answer.code() shouldBe "SRT-004"
        rows(f) shouldBe tables.size
        registryState(f) shouldBe "ATTACHED"
        operations(f) shouldBe emptyList()
        store.objects.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-22964 an open notice window gives 409 EVD-006 and the rows remain`() {
        val f = fixture(gap = Gap.WINDOW_OPEN)
        val answer = delete(f)
        answer.status() shouldBe 409
        answer.code() shouldBe "EVD-006"
        answer.detail() shouldBe null
        rows(f) shouldBe tables.size
        operations(f).single().take(3) shouldBe listOf("FAILED", STARTER, "EVD-006")
    }

    @Test
    fun `INV-AC-22964 missing approvals give 409 EVD-007 and the rows remain`() {
        val f = fixture(gap = Gap.NO_APPROVALS)
        val answer = delete(f)
        answer.status() shouldBe 409
        answer.code() shouldBe "EVD-007"
        answer.detail() shouldBe null
        rows(f) shouldBe tables.size
        operations(f).single().take(3) shouldBe listOf("FAILED", STARTER, "EVD-007")
    }

    @Test
    fun `INV-AC-22964 the stop codes are members of the generated catalogue`() {
        val known =
            com.dynokore.gst.catalogue.ErrorCode.entries
                .map { it.code }
                .toSet()
        listOf("EVD-003", "EVD-004", "EVD-005", "EVD-006", "EVD-007", "EVD-008", "EVD-009").forEach {
            (it in known) shouldBe true
        }
    }

    @Test
    fun `INV-AC-22964 a repeat gives 409 and deletes nothing`() {
        val f = fixture()
        deleted(f)
        val repeat = delete(f)
        repeat.status() shouldBe 409
        repeat.code() shouldBe "EVD-008"
        repeat.detail() shouldBe null
        operations(f).size shouldBe 1
        sql("select count(*) from evidence.outbox where tenant_id = ? and type like '%AuditEntryRecorded%'", f.tenant)
            .single()
            .single() shouldBe "1"
    }

    @Test
    fun `INV-AC-22964 a deletion held past the contract timeout answers 202 and a concurrent call gives 409`() {
        val f = fixture()
        val gate = CountDownLatch(1)
        store.gate = gate
        var first: Pair<HttpResponse<String>, JsonNode>? = null
        val caller = thread { first = delete(f) }
        awaitState(f, "RUNNING")
        // a concurrent second call while the first is running: 409 and nothing is started
        val second = delete(f)
        second.status() shouldBe 409
        second.code() shouldBe "EVD-009"
        second.detail() shouldBe null
        operations(f).size shouldBe 1
        caller.join(JOIN_MILLIS)
        val accepted = checkNotNull(first)
        accepted.status() shouldBe 202
        accepted.second.get("state").asString() shouldBe "RUNNING"
        accepted.first
            .headers()
            .firstValue("Location")
            .get() shouldBe
            "/internal/v1/evidence/partitions/${f.partitionId}/deletion"
        accepted.first
            .headers()
            .firstValue("Retry-After")
            .isPresent shouldBe true
        // the starter reads RUNNING, another principal is refused
        read(f).second.get("state").asString() shouldBe "RUNNING"
        read(f, OTHER).status() shouldBe 404
        gate.countDown()
        awaitState(f, "SUCCEEDED")
        val done = read(f)
        done.second.get("state").asString() shouldBe "SUCCEEDED"
        rows(f) shouldBe 0
        registryState(f) shouldBe "DROPPED"
    }

    @Test
    fun `INV-AC-22964 a deletion whose detach is refused leaves the row FAILED and a retry is accepted`() {
        val f = fixture()
        val member =
            sql(
                "select partition_table from evidence.retention_partition_member " +
                    "where partition_id = ? and chain_table = 'audit_log'",
                f.partitionId,
            ).single().single()!!
        sql("alter table evidence.$member rename to ${member}_moved")
        val refused = delete(f)
        refused.status() shouldBe 500
        refused.code() shouldBe "SRT-010"
        rows(f) shouldBe tables.size
        registryState(f) shouldBe "ATTACHED"
        val failed = operations(f).single()
        failed.take(3) shouldBe listOf("FAILED", STARTER, "SRT-010")
        failed[3] shouldBe "true"
        val status = read(f)
        status.second.get("state").asString() shouldBe "FAILED"
        status.second
            .get("error")
            .get("code")
            .asString() shouldBe "SRT-010"
        sql("alter table evidence.${member}_moved rename to $member")
        deleted(f)
        operations(f).map { it[0] } shouldBe listOf("FAILED", "SUCCEEDED")
        rows(f) shouldBe 0
    }

    @Test
    fun `INV-AC-22964 a failure of another method than getMaxEvidenceRetention is no 503`() {
        val f = fixture()
        store.failPuts = true
        val answer = delete(f)
        answer.status() shouldBe 500
        answer.code() shouldBe "SRT-010"
        rows(f) shouldBe tables.size
        store.failPuts = false
        deleted(f)
    }

    @Test
    fun `INV-AC-22964 a read after the visibility period is refused as not found`() {
        val f = fixture()
        deleted(f)
        read(f).status() shouldBe 200
        // the trigger refuses an update of an ended row for everyone: the period is moved back with it disabled
        EvidenceDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.execute("alter table $OPERATION disable trigger $GUARD")
                val moved =
                    runCatching {
                        s.execute(
                            "update $OPERATION set started_at = started_at - interval '8 days', " +
                                "completed_at = completed_at - interval '8 days' where tenant_id = '${f.tenant}'",
                        )
                    }
                s.execute("alter table $OPERATION enable trigger $GUARD")
                moved.getOrThrow()
            }
        }
        val gone = read(f)
        gone.status() shouldBe 404
        gone.code() shouldBe "SRT-005"
    }

    @Test
    fun `INV-AC-22964 a visibility value below seven days stops the start`() {
        val failure =
            runCatching {
                EvidenceContext
                    .startServlet(
                        Files.createTempDirectory(dir, "short"),
                        listOf(WorkloadTokensOnly::class.java, GateStoreBeans::class.java),
                        arrayOf(
                            "evidence.identity-provider.jwk-set-uri=${TestIdentity.jwkSetUri}",
                            "evidence.retention.deletion-status-visibility=P6D",
                        ),
                        PartitionDeletionHttpTest::class.java.classLoader,
                    ).close()
            }.exceptionOrNull()
        (failure != null) shouldBe true
        val text = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        text shouldContain "at least"
    }

    @Test
    fun `INV-AC-22964 an unset archive lock period refuses the deletion with 500 SRT-010 and writes no object`() {
        // the context binds a fixed port: the configured service is stopped while the one without the period runs
        val beans = listOf(WorkloadTokensOnly::class.java, GateStoreBeans::class.java)
        service.close()
        System.clearProperty(LOCK_PROPERTY)
        service = RetentionService.start(Files.createTempDirectory(dir, "unset"), beans)
        try {
            val f = fixture()
            val refused = delete(f)
            refused.status() shouldBe 500
            refused.code() shouldBe "SRT-010"
            store.objects.isEmpty() shouldBe true
            rows(f) shouldBe tables.size
            registryState(f) shouldBe "ATTACHED"
        } finally {
            service.close()
            System.setProperty(LOCK_PROPERTY, LOCK_PERIOD)
            service = RetentionService.start(Files.createTempDirectory(dir, "restored"), beans)
        }
    }

    @Test
    fun `INV-AC-22964 a negative archive lock period stops the start`() {
        service.close() // the contexts bind fixed ports
        val beans = listOf(WorkloadTokensOnly::class.java, GateStoreBeans::class.java)
        System.setProperty(LOCK_PROPERTY, "-PT1H")
        try {
            val failure =
                runCatching {
                    EvidenceContext
                        .startServlet(
                            Files.createTempDirectory(dir, "negative"),
                            beans,
                            arrayOf("evidence.identity-provider.jwk-set-uri=${TestIdentity.jwkSetUri}"),
                            PartitionDeletionHttpTest::class.java.classLoader,
                        ).close()
                }.exceptionOrNull()
            (failure != null) shouldBe true
            val text = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            text shouldContain "must not be negative"
        } finally {
            System.setProperty(LOCK_PROPERTY, LOCK_PERIOD)
            service = RetentionService.start(Files.createTempDirectory(dir, "after-negative"), beans)
        }
    }

    private companion object {
        const val LOCK_PROPERTY = "evidence.retention.archive-lock-period"
        const val LOCK_PERIOD = "PT1H"
        const val ENTITY = "ENT-A"
        val PER_REQUEST = listOf("correlationId", "correlation_id", "instance", "traceId")
        const val PATH = "/internal/v1/evidence/partitions/"
        const val OPERATION = "evidence.partition_deletion_operation"
        const val GUARD = "partition_deletion_operation_running_only"
        const val STARTER = "u-starter"
        const val OTHER = "u-other"
        const val REQUEST_ROLE = "platform.engineer"
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 50L
        const val JOIN_MILLIS = 10_000L
    }
}
