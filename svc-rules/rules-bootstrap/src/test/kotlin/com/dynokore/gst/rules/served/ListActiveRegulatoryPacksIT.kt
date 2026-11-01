package com.dynokore.gst.rules.served

import com.dynokore.gst.rules.application.ActivePack
import com.dynokore.gst.rules.application.ActivePackPosition
import com.dynokore.gst.rules.application.ActivePackReader
import com.dynokore.gst.rules.bootstrap.PostgresRules
import com.dynokore.gst.rules.bootstrap.RulesApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"
private const val TENANT = "0a000000-0000-0000-0000-00000000000a"
private const val PATH = "/internal/v1/rules/regulatory-packs/active"
private const val TABLES =
    "select table_name from information_schema.tables where table_schema = 'rules' and table_type = 'BASE TABLE'"
private val CALLERS = listOf("installer", "canonical-svc", "console-bff", "installer-alt")

/** Counts the reads the use case makes: it is reached only when the caller check lets the call through. */
@TestConfiguration(proxyBeanMethods = false)
class CountingReader {
    @Bean
    fun readCounter(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is ActivePackReader) {
                    ActivePackReader { after: ActivePackPosition?, limit: Int ->
                        reads.incrementAndGet()
                        bean.page(after, limit)
                    }
                } else {
                    bean
                }
        }

    companion object {
        val reads = AtomicInteger()
    }
}

/**
 * `listActiveRegulatoryPacks` on real PostgreSQL through the served application (INV-T-9880, INV-REQ-1105 behavior 1;
 * INV-AC-2089 is run by the installer's task INV-T-9868 and carried here as a deferred row).
 */
@SpringBootTest(
    classes = [ServedDoubles::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(CountingReader::class)
class ListActiveRegulatoryPacksIT(
    @LocalServerPort private val port: Int,
) {
    private val http = HttpClient.newHttpClient()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val key = PostgresRules.secrets.resolve("rules/active-packs-cursor-key")
            Files.createDirectories(key.parent)
            Files.writeString(key, "test-only-cursor-key-0123456789abcdef")
            PostgresRules.register(registry)
            registry.add("service-runtime.modules.schema") { "rules" }
            registry.add("service-runtime.idempotency.schema") { "rules" }
            registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
            CALLERS.forEachIndexed { i, service ->
                registry.add("service-runtime.workload-identity.callers[$i].service") { service }
                registry.add("service-runtime.workload-identity.callers[$i].subject") { subjectOf(service) }
            }
        }
    }

    private fun superuser() =
        DriverManager.getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)

    private fun sql(
        statement: String,
        vararg binds: Any?,
    ) = superuser().use {
        // the superuser seeds directly: the triggers that need a signed binding are switched off for the session
        it.createStatement().use { s -> s.execute("set session_replication_role = replica") }
        it.prepareStatement(statement).use { st ->
            binds.forEachIndexed { i, b -> st.setObject(i + 1, b) }
            st.execute()
        }
    }

    private data class Row(
        val tenant: String,
        val layer: String,
        val type: String,
        val version: String,
        val status: String,
        val minEngine: String = "2026.3",
        val to: String? = null,
    )

    private fun seed(row: Row) =
        sql(
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "effective_from, effective_to, min_engine_version, registered_by) " +
                "values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, '2026-04-01'::date, ?::date, ?, 'seed')",
            UUID.randomUUID(),
            row.tenant,
            "${row.type}-${row.version}",
            row.type,
            row.version,
            row.layer,
            row.status,
            row.to,
            row.minEngine,
        )

    private fun regulatory(
        type: String,
        version: String,
        status: String,
        minEngine: String = "2026.3",
        to: String? = null,
    ) = seed(Row(PLATFORM, "REGULATORY", type, version, status, minEngine, to))

    @BeforeEach
    fun clean() {
        sql("delete from rules.pack_entry_source")
        sql("delete from rules.pack_registry")
        CountingReader.reads.set(0)
    }

    private fun get(
        query: String = "",
        workload: String? = workloadToken("installer"),
        authorization: String? = null,
    ): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$PATH$query")).GET()
        workload?.let { b.header("X-Workload-Token", it) }
        authorization?.let { b.header("Authorization", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun items(body: String): List<String> =
        Regex("""\{"packType":"([^"]+)","version":"([^"]+)","status":"([^"]+)","minEngineVersion":"([^"]+)"([^}]*)}""")
            .findAll(body)
            .map { m ->
                "${m.groupValues[1]}|${m.groupValues[2]}|${m.groupValues[3]}|${m.groupValues[4]}|${m.groupValues[5]}"
            }.toList()

    @Test
    fun `lists only the ARMED regulatory rows ordered by type then version`() {
        regulatory("SCOPE", "2026.4", "ARMED", "2026.3")
        regulatory("SCOPE", "2026.2", "ARMED", "2025.9", to = "2027-01-01")
        regulatory("CODES", "1.0", "ARMED", "2026.1")
        regulatory("SCOPE", "2026.5", "STAGED")
        regulatory("SCOPE", "2026.6", "ACKNOWLEDGED")
        regulatory("SCOPE", "2026.1", "SUPERSEDED")
        regulatory("SCOPE", "2026.0", "ABORTED")
        seed(Row(TENANT, "TENANT_MAPPING", "MAPPING", "9.9", "ARMED", "9999.9"))
        val answer = get()
        answer.statusCode() shouldBe 200
        items(answer.body()) shouldBe
            listOf(
                "CODES|1.0|ARMED|2026.1|,\"effectiveFrom\":\"2026-04-01\"",
                "SCOPE|2026.2|ARMED|2025.9|,\"effectiveFrom\":\"2026-04-01\",\"effectiveTo\":\"2027-01-01\"",
                "SCOPE|2026.4|ARMED|2026.3|,\"effectiveFrom\":\"2026-04-01\"",
            )
        answer.body() shouldNotBe null
        (answer.body().contains("nextCursor")) shouldBe false
        (answer.body().contains("9999.9")) shouldBe false
    }

    @Test
    fun `pages 501 armed rows by cursor and refuses a foreign cursor and an out-of-range limit`() {
        (1..501).forEach { regulatory("SCOPE", "v%04d".format(java.util.Locale.ROOT, it), "ARMED") }
        val first = get("?limit=500")
        first.statusCode() shouldBe 200
        items(first.body()).size shouldBe 500
        val cursor = Regex(""""nextCursor":"([^"]+)"""").find(first.body())!!.groupValues[1]
        val second = get("?limit=500&cursor=$cursor")
        second.statusCode() shouldBe 200
        items(second.body()) shouldBe listOf("SCOPE|v0501|ARMED|2026.3|,\"effectiveFrom\":\"2026-04-01\"")
        second.body().contains("nextCursor") shouldBe false
        items(get().body()).size shouldBe 100
        get("?cursor=not-issued-by-the-service").statusCode() shouldBe 400
        get("?cursor=${cursor.substringBefore('.')}.AAAA").statusCode() shouldBe 400
        get("?cursor=${cursor.substringBefore('.')}").statusCode() shouldBe 400
        get("?limit=501").statusCode() shouldBe 400
        get("?limit=0").statusCode() shouldBe 400
    }

    private fun refusedWith(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
    ) {
        answer.statusCode() shouldBe status
        answer.body() shouldContain code
    }

    @Test
    fun `the workload of canonical-svc is refused with SRT-004`() =
        refusedWith(get(workload = workloadToken("canonical-svc")), 403, "SRT-004")

    @Test
    fun `the workload of console-bff is refused with SRT-004`() =
        refusedWith(get(workload = workloadToken("console-bff")), 403, "SRT-004")

    @Test
    fun `the look-alike installer-alt is refused with SRT-004`() =
        refusedWith(get(workload = workloadToken("installer-alt")), 403, "SRT-004")

    @Test
    fun `no workload token is refused with 401`() = get(workload = null).statusCode() shouldBe 401

    @Test
    fun `an end-user token without a workload token is refused with 401`() =
        get(workload = null, authorization = USER_A_TOKEN).statusCode() shouldBe 401

    private fun snapshot(): Map<String, List<String>> =
        superuser().use { c ->
            val tables = mutableListOf<String>()
            c.createStatement().use { s ->
                s.executeQuery(TABLES).use {
                    while (it.next()) tables += it.getString(1)
                }
            }
            tables.sorted().associateWith { t ->
                c.createStatement().use { s ->
                    s.executeQuery("select t::text from rules.\"$t\" t order by 1").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        }

    @Test
    fun `a refused caller leaves every table of the schema rules equal and the use case unreached`() {
        regulatory("SCOPE", "2026.4", "ARMED")
        val before = snapshot()
        before.keys.contains("pack_registry") shouldBe true
        get(workload = workloadToken("installer-alt")).statusCode() shouldBe 403
        get(workload = null).statusCode() shouldBe 401
        CountingReader.reads.get() shouldBe 0
        snapshot() shouldBe before
        // the named caller is not refused and reaches the use case: the refusal is specific to the caller
        get().statusCode() shouldBe 200
        CountingReader.reads.get() shouldBe 1
        snapshot() shouldBe before
    }

    @Test
    fun `probe - the comparison can fail when a call writes one row to the outbox`() {
        val before = snapshot()
        sql(
            "insert into rules.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, " +
                "instance_id, correlation_id, causation_id, data) " +
                "values ('probe', 't', 'T', 's', 'x', now(), ?::uuid, 'e', 'i', 'c', 'c', '{}'::jsonb)",
            TENANT,
        )
        (snapshot() == before) shouldBe false
        sql("delete from rules.outbox")
    }
}
