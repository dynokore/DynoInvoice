package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.persistence.JooqArchiveVolumeLedger
import com.dynokore.gst.evidence.adapters.persistence.JooqStoredContentBytes
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DecisionTrace.Companion.DECISION_TRACE
import com.dynokore.gst.evidence.application.archive.ArchiveVolumePublisher
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The archive-volume store against a real database (P109), on the TPL-18 context over the `evidence_runtime` login:
 * the catalogue shape of `V7__evidence_archive_volume.sql`, the content-column sum (INV-Q-12681 A) and the
 * transactional publication through the real outbox writer (INV-Q-12683 A, INV-Q-12736 A).
 */
class ArchiveVolumeDbTest {
    private val actor = "svc:evidence-svc"
    private val eventType = ArchiveVolumePublisher.EVENT_TYPE
    private val instant = Instant.parse("2026-10-29T01:02:03Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        EvidenceContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun <T> Boundary.inTenant(
        tenant: UUID,
        block: () -> T,
    ): T = scope.bind(TenantId(tenant), actor) { runner.inTransaction { block() } }

    private fun catalogue(withType: Boolean): EventCatalogue =
        EventCatalogue(
            EvidenceEvents.entries +
                if (withType) {
                    listOf(
                        CatalogueEntry(
                            eventType,
                            "gst.evidence.archive-volume-measured.v1",
                            setOf("evidence-svc"),
                            contentTopic = false,
                            retainedWhileResolvable = false,
                        ),
                    )
                } else {
                    emptyList()
                },
        )

    private fun Boundary.publisher(withType: Boolean = true) =
        ArchiveVolumePublisher(
            JooqArchiveVolumeLedger(dsl),
            JdbcOutboxWriter(dsl, EvidenceDb.SCHEMA, "evidence-svc", "evidence-svc-0", catalogue(withType), clock),
            clock,
        )

    private fun Boundary.publish(
        tenant: UUID,
        entity: String,
        bytes: Long,
        correlation: String,
        causation: String,
        withType: Boolean = true,
    ) = inTenant(tenant) {
        publisher(withType).publish(TenantId(tenant), entity, bytes, CorrelationId(correlation), CausationId(causation))
    }

    // ---- seeding as the superuser (row-level security does not apply to it) ----

    private fun <T> superuser(block: (Connection) -> T): T = EvidenceDb.superuser().use(block)

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun seedTrace(
        tenant: UUID,
        entity: String,
        length: Int,
        table: String = "decision_trace",
    ) = superuser {
        it.run(
            "insert into evidence.$table (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, " +
                "content_hash, recorded_at) values (gen_random_uuid(), '$tenant', '$entity', '2026-Q3', " +
                "(select coalesce(max(seq), 0) + 1 from evidence.$table where tenant_id = '$tenant' and " +
                "entity_ref = '$entity'), 'DECISION_TRACE', convert_to(repeat('a', $length), 'UTF8'), 'h', now())",
        )
    }

    private fun seedPending(
        tenant: UUID,
        entity: String,
        length: Int,
    ) = superuser {
        val doc = UUID.randomUUID()
        it.run(
            "insert into evidence.document_locator (tenant_id, doc_uuid, version, entity_ref, period_ref, " +
                "transaction_date, trace_reference, source_event_id, recorded_at) values ('$tenant', '$doc', 1, " +
                "'$entity', '2026-Q3', current_date, 't', 'loc-$doc', now())",
        )
        it.run(
            "insert into evidence.pending_record (tenant_id, source_event_id, kind, doc_uuid, version, event_json, " +
                "arrived_at) values ('$tenant', 'pend-$doc', 'DECISION_TRACE', '$doc', 1, repeat('b', $length), now())",
        )
    }

    private fun deleteContent(
        tenant: UUID,
        entity: String,
    ) = superuser {
        it.run("set session_replication_role = replica")
        it.run("delete from evidence.decision_trace where tenant_id = '$tenant' and entity_ref = '$entity'")
    }

    private fun count(sql: String): Int =
        superuser { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun listed(tenant: UUID): Set<String> =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select tenant_id, entity_ref from evidence.archive_volume_entities()").use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }
                        .filter { it.first == tenant.toString() }
                        .map { it.second }
                        .toSet()
                }
            }
        }

    private data class OutboxRow(
        val type: String,
        val tenant: String,
        val entity: String,
        val correlation: String,
        val causation: String,
        val data: String,
    )

    private fun outbox(tenant: UUID): List<OutboxRow> =
        superuser { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select type, tenant_id, entity_ref, correlation_id, causation_id, data::text " +
                            "from evidence.outbox where tenant_id = '$tenant' order by seq",
                    ).use { r ->
                        generateSequence {
                            if (r.next()) {
                                OutboxRow(
                                    r.getString(1),
                                    r.getString(2),
                                    r.getString(3),
                                    r.getString(4),
                                    r.getString(5),
                                    r.getString(6),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
            }
        }

    // ---- the catalogue shape of the migration ----

    private fun Connection.text(sql: String): String? =
        createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    private val fn = "'evidence.archive_volume_entities()'::regprocedure"

    private val table = "'evidence.archive_volume_published'::regclass"

    /** Each promise of the migration: its label, a catalogue query and the one value it must return. */
    private val promises: List<Triple<String, String, String>> =
        listOf(
            Triple("not security definer", "select prosecdef::text from pg_proc where oid = $fn", "true"),
            Triple("owner", "select pg_get_userbyid(proowner) from pg_proc where oid = $fn", "evidence_sys"),
            Triple(
                "result columns",
                "select pg_get_function_result(oid) from pg_proc where oid = $fn",
                "TABLE(tenant_id uuid, entity_ref text)",
            ),
            Triple(
                "search_path",
                "select proconfig::text from pg_proc where oid = $fn",
                "{\"search_path=pg_catalog, evidence, pg_temp\"}",
            ),
            Triple("public execute", "select has_function_privilege('public', $fn, 'execute')::text", "false"),
            Triple(
                "runtime execute",
                "select has_function_privilege('evidence_runtime', $fn, 'execute')::text",
                "true",
            ),
            Triple("rls not enabled", "select relrowsecurity::text from pg_class where oid = $table", "true"),
            Triple("rls not forced", "select relforcerowsecurity::text from pg_class where oid = $table", "true"),
            Triple(
                "tenant policy",
                "select count(*)::text from pg_policies where schemaname = 'evidence' and " +
                    "tablename = 'archive_volume_published' and policyname = 'tenant_isolation' and " +
                    "qual like '%bound_tenant%' and with_check like '%bound_tenant%'",
                "1",
            ),
            Triple("runtime select", "select has_table_privilege('evidence_runtime', $table, 'select')::text", "true"),
            Triple("runtime insert", "select has_table_privilege('evidence_runtime', $table, 'insert')::text", "true"),
            Triple("runtime update", "select has_table_privilege('evidence_runtime', $table, 'update')::text", "true"),
            Triple("runtime delete", "select has_table_privilege('evidence_runtime', $table, 'delete')::text", "false"),
            Triple("public select", "select has_table_privilege('public', $table, 'select')::text", "false"),
        )

    /** What the migration promises, read from the catalogue; empty when every promise holds. */
    private fun violations(c: Connection): List<String> =
        promises.filter { (_, sql, expected) -> c.text(sql) != expected }.map { it.first }

    @Test
    fun `the migration leaves the function and the table as specified`() {
        superuser { violations(it) shouldBe emptyList() }
    }

    private fun probe(mutation: String): List<String> =
        EvidenceDb.withScratchDatabase("") { url ->
            EvidenceDb.superuserOf(url).use {
                it.run(mutation)
                violations(it)
            }
        }

    @Test
    fun `a function that is not security definer is caught`() {
        probe("alter function evidence.archive_volume_entities() security invoker") shouldBe
            listOf("not security definer")
    }

    @Test
    fun `a third result column is caught`() {
        probe(
            "drop function evidence.archive_volume_entities(); " +
                "create function evidence.archive_volume_entities() returns table (tenant_id uuid, entity_ref text, " +
                "extra text) language sql security definer set search_path = pg_catalog, evidence, pg_temp as " +
                "\$\$ select null::uuid, null::text, null::text \$\$; " +
                "alter function evidence.archive_volume_entities() owner to evidence_sys; " +
                "revoke all on function evidence.archive_volume_entities() from public; " +
                "grant execute on function evidence.archive_volume_entities() to evidence_runtime",
        ) shouldBe listOf("result columns")
    }

    @Test
    fun `execute left with public is caught`() {
        probe("grant execute on function evidence.archive_volume_entities() to public") shouldBe
            listOf("public execute")
    }

    @Test
    fun `a missing tenant policy is caught`() {
        probe("drop policy tenant_isolation on evidence.archive_volume_published") shouldBe listOf("tenant policy")
    }

    // ---- the content-column sum ----

    @Test
    fun `the stored content bytes add the covered columns of one entity of one tenant`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        seedTrace(tenant, "E-1", 200)
        seedPending(tenant, "E-1", 50)
        seedTrace(tenant, "E-2", 7000)
        seedTrace(other, "E-1", 5000)
        val sum = JooqStoredContentBytes(boundary.dsl)
        boundary.inTenant(tenant) { sum.bytes(TenantId(tenant), "E-1") } shouldBe 350L
        boundary.inTenant(tenant) { sum.bytes(TenantId(tenant), "E-3") } shouldBe 0L
    }

    @Test
    fun `the stored content bytes add every covered table`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        listOf("decision_trace", "evidence_payload", "acknowledgement", "audit_log").forEachIndexed { i, table ->
            seedTrace(tenant, "E-1", 10 * (i + 1), table)
        }
        seedPending(tenant, "E-1", 1000)
        boundary.inTenant(tenant) { JooqStoredContentBytes(boundary.dsl).bytes(TenantId(tenant), "E-1") } shouldBe 1100L
    }

    @Test
    fun `another tenant context returns zero and reads no row of the first tenant`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        seedTrace(first, "E-1", 100)
        seedPending(first, "E-1", 50)
        boundary.inTenant(second) {
            JooqStoredContentBytes(boundary.dsl).bytes(TenantId(first), "E-1") shouldBe 0L
            boundary.dsl.fetchCount(DECISION_TRACE, DECISION_TRACE.TENANT_ID.eq(first)) shouldBe 0
        }
        boundary.inTenant(first) { boundary.dsl.fetchCount(DECISION_TRACE) } shouldBe 1
    }

    // ---- the publication ----

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `one call writes the ledger row and the outbox row with the passed trace ids`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        boundary.publish(tenant, "E-1", 350, "corr-test-1", "cause-test-1")
        val rows = outbox(tenant)
        rows.size shouldBe 1
        val row = rows.single()
        row.type shouldBe eventType
        row.tenant shouldBe tenant.toString()
        row.entity shouldBe "E-1"
        row.correlation shouldBe "corr-test-1"
        row.causation shouldBe "cause-test-1"
        val payload = mapper.readTree(row.data)
        payload.get("tenantRef").asString() shouldBe tenant.toString()
        payload.get("entityRef").asString() shouldBe "E-1"
        payload.get("bytes").asLong() shouldBe 350L
        payload.get("measuredAt").asString() shouldBe "2026-10-29T01:02:03Z"
        count(
            "select count(*) from evidence.archive_volume_published where tenant_id = '$tenant' and bytes = 350",
        ) shouldBe
            1
        boundary.inTenant(tenant) { JooqArchiveVolumeLedger(boundary.dsl).last(TenantId(tenant), "E-1") } shouldBe 350L
    }

    @Test
    fun `a second call updates the ledger row and leaves its own outbox row with its own trace ids`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        boundary.publish(tenant, "E-1", 350, "corr-test-1", "cause-test-1")
        val first = outbox(tenant).single()
        boundary.publish(tenant, "E-1", 0, "corr-test-2", "cause-test-2")
        val rows = outbox(tenant)
        rows.size shouldBe 2
        rows[0] shouldBe first
        rows[1].correlation shouldBe "corr-test-2"
        rows[1].causation shouldBe "cause-test-2"
        mapper.readTree(rows[1].data).get("bytes").asLong() shouldBe 0L
        count("select count(*) from evidence.archive_volume_published where tenant_id = '$tenant'") shouldBe 1
        count("select bytes from evidence.archive_volume_published where tenant_id = '$tenant'") shouldBe 0
    }

    @Test
    fun `a failed outbox append leaves no ledger row`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> {
            boundary.publish(tenant, "E-1", 350, "corr-test-1", "cause-test-1", withType = false)
        }
        count("select count(*) from evidence.archive_volume_published where tenant_id = '$tenant'") shouldBe 0
        outbox(tenant) shouldBe emptyList()
    }

    @Test
    fun `the entity list keeps a pair whose content is deleted while its last bytes are above zero`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        listed(tenant) shouldBe setOf("E-1")
        boundary.publish(tenant, "E-1", 100, "corr-test-1", "cause-test-1")
        deleteContent(tenant, "E-1")
        listed(tenant) shouldBe setOf("E-1")
    }

    @Test
    fun `the entity list drops the pair once zero bytes are recorded`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        boundary.publish(tenant, "E-1", 100, "corr-test-1", "cause-test-1")
        deleteContent(tenant, "E-1")
        boundary.publish(tenant, "E-1", 0, "corr-test-2", "cause-test-2")
        listed(tenant) shouldBe emptySet()
    }

    @Test
    fun `the entity list names the entity of a pending record through its locator`(
        @TempDir dir: Path,
    ) = run(dir) { _ ->
        val tenant = UUID.randomUUID()
        seedPending(tenant, "E-9", 10)
        listed(tenant) shouldBe setOf("E-9")
    }

    // ---- the payload schema (INV-Q-12746 A) ----

    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    /** Fails closed: an unset property or a missing file is an error, never a skip. */
    private fun schemaText(): String {
        val dir = checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" }
        val file = File(dir, "schemas/archive-volume-measured-payload.schema.json")
        check(file.isFile) { "schema file does not exist: $file" }
        return file.readText()
    }

    private fun schemaErrors(data: JsonNode): List<String> =
        schemas.getSchema(schemaText()).validate(data).map { it.toString() }

    /** The `data` column of the outbox row the real writer wrote. */
    private fun writtenData(boundary: Boundary): ObjectNode {
        val tenant = UUID.randomUUID()
        boundary.publish(tenant, "E-1", 350, "corr-test-1", "cause-test-1")
        return mapper.readTree(outbox(tenant).single().data) as ObjectNode
    }

    @Test
    fun `the written payload has no schema error`(
        @TempDir dir: Path,
    ) = run(dir) { boundary -> schemaErrors(writtenData(boundary)) shouldBe emptyList() }

    @Test
    fun `the payload without a required property has a schema error`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        for (name in listOf("tenantRef", "entityRef", "bytes", "measuredAt")) {
            val data = writtenData(boundary)
            data.remove(name)
            check(schemaErrors(data).isNotEmpty()) { "no error with $name removed" }
        }
    }

    @Test
    fun `a negative bytes and a malformed measuredAt each have a schema error`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val negative = writtenData(boundary).also { it.put("bytes", -1) }
        check(schemaErrors(negative).isNotEmpty()) { "no error for bytes -1" }
        val badTime = writtenData(boundary).also { it.put("measuredAt", "not-a-time") }
        check(schemaErrors(badTime).isNotEmpty()) { "no error for measuredAt not-a-time" }
    }

    @Test
    fun `an unset contract directory or a missing schema file fails and does not skip`() {
        val saved = System.getProperty("gst.contract.dir")
        try {
            System.clearProperty("gst.contract.dir")
            shouldThrow<IllegalStateException> { schemaText() }
            System.setProperty("gst.contract.dir", File(saved ?: ".", "no-such-dir").path)
            shouldThrow<IllegalStateException> { schemaText() }
        } finally {
            if (saved != null) System.setProperty("gst.contract.dir", saved)
        }
    }
}
