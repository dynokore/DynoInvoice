package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.gst.transport.adapters.http.RouteCaller
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.EntityAdapter.Companion.ENTITY_ADAPTER
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

private const val BFF_SUBJECT = "system:serviceaccount:platform:console-bff"
private const val BFF_TOKEN = "W-bff"

/**
 * `failoverAdapter`, `setConnectionEntities` and `testConnection` over real HTTP, a real database (the jOOQ stores, the
 * SUB-018 lookup, the outbox) and the certification-kit AP simulator (INV-T-18675; INV-REQ-796, INV-REQ-835,
 * INV-REQ-836). The AuthorityReplica is the real one, fed per test with the grants of the caller.
 */
class AdapterAdministrationBoundaryTest {
    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun resetSimulator() = ApSimulator.reset()

    private class Running(
        val context: ConfigurableApplicationContext,
        val harness: Harness,
        val port: Int,
    ) {
        val authority: AuthorityReplica = context.getBean(AuthorityReplica::class.java)
        private var sequence = 0L

        /** A caller of [tenant] holding exactly the permission keys [keys] (TENANT scope); gives its Authorization. */
        fun user(
            tenant: UUID,
            vararg keys: String,
        ): String = userOf(tenant, ENTITIES, *keys)

        /** As [user], with the entities [entities] in the entity set of the principal (API-24). */
        fun userOf(
            tenant: UUID,
            entities: Set<String>,
            vararg keys: String,
        ): String {
            val header = TestPrincipals.register(tenant, entities)
            if (keys.isNotEmpty()) {
                val since = Clock.systemUTC().instant().minusSeconds(60)
                val grants = keys.map { Grant(it, ScopeLevel.TENANT, "x", since) }
                val record = AuthorityRecord.GrantRecord("tenant-wide", grants)
                authority.accept(AuthorityChanged(TenantId(tenant), TestPrincipals.actorOf(header), ++sequence, record))
            }
            return header
        }
    }

    private fun run(
        dir: Path,
        vararg overrides: String,
        block: (Running) -> Unit,
    ) {
        TransportContext.secrets(dir)
        val primary = AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
        val secondary = AdapterId("s-${UUID.randomUUID().toString().take(UNIQUE)}")
        val properties =
            TransportContext.propertyValues(dir, TransportDb.jdbcUrl) +
                listOf(
                    "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}",
                    "transport.ap.adapters[0].id=${primary.value}",
                    "transport.ap.adapters[0].path=primary",
                    "transport.ap.adapters[1].id=${secondary.value}",
                    "transport.ap.adapters[1].path=secondary",
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=$BFF_SUBJECT",
                )
        val context =
            SpringApplicationBuilder(TransportApplication::class.java, CallerTestConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .properties(*properties.toTypedArray())
                .run("--server.port=0", "--management.server.port=0", *overrides.map { "--$it" }.toTypedArray())
        try {
            val port = checkNotNull((context as WebServerApplicationContext).webServer).port
            val running = Running(context, Harness(context, primary, secondary), port)
            running.authority.onSnapshotComplete(0)
            block(running)
        } finally {
            context.close()
        }
    }

    private class Answer(
        val status: Int,
        val body: String,
    )

    private fun Running.send(
        method: String,
        path: String,
        body: String?,
        authorization: String?,
        key: String = UUID.randomUUID().toString(),
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header(WorkloadToken.HEADER_NAME, BFF_TOKEN)
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        authorization?.let { request.header("Authorization", it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun Running.failover(
        body: String,
        user: String?,
        adapter: String = harness.primary.value,
    ) = send("POST", "$ADAPTERS/$adapter/failovers", body, user)

    private fun Running.setEntities(
        connection: String,
        refs: List<String>,
        user: String?,
        key: String = UUID.randomUUID().toString(),
    ): Answer {
        val body = """{"entityRefs":${mapper.writeValueAsString(refs)}}"""
        return send("PUT", "$CONNECTIONS/$connection/entities", body, user, key)
    }

    private fun Running.testConnection(
        adapter: String,
        user: String?,
    ) = send("POST", "$ADAPTERS/$adapter/connection-tests", null, user)

    private fun Running.tenantWithConnections(): UUID {
        val tenant = UUID.randomUUID()
        harness.connect(tenant, harness.primary, "10")
        harness.connect(tenant, harness.secondary, "10")
        return tenant
    }

    private fun Running.assigned(
        tenant: UUID,
        entity: String,
    ): String? =
        harness.asTenant(tenant) {
            harness
                .dsl
                .select(ENTITY_ADAPTER.ADAPTER_ID)
                .from(ENTITY_ADAPTER)
                .where(ENTITY_ADAPTER.ENTITY_REF.eq(entity))
                .fetchOne(ENTITY_ADAPTER.ADAPTER_ID)
        }

    private fun Running.assignedAt(
        tenant: UUID,
        entity: String,
    ) = harness.asTenant(tenant) {
        harness
            .dsl
            .select(ENTITY_ADAPTER.ASSIGNED_AT)
            .from(ENTITY_ADAPTER)
            .where(ENTITY_ADAPTER.ENTITY_REF.eq(entity))
            .fetchOne(ENTITY_ADAPTER.ASSIGNED_AT)
    }

    private fun Running.storedEntities(
        tenant: UUID,
        connection: String,
    ): List<String> =
        harness.asTenant(tenant) {
            harness
                .dsl
                .select(AP_CONNECTION.ENTITY_REFS)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(tenant))
                .and(AP_CONNECTION.ADAPTER_ID.eq(connection))
                .fetchOne(AP_CONNECTION.ENTITY_REFS)
                .orEmpty()
                .map { checkNotNull(it) }
        }

    private fun Running.topics(
        tenant: UUID,
        part: String,
    ): List<String> =
        harness.asTenant(tenant) {
            harness
                .dsl
                .select(Outbox.OUTBOX.TOPIC, Outbox.OUTBOX.DATA)
                .from(Outbox.OUTBOX)
                .orderBy(Outbox.OUTBOX.SEQ)
                .fetch()
                .filter { it.value1()?.contains(part) == true }
                .map { checkNotNull(it.value2()).data() }
        }

    private fun Running.routeEvents(tenant: UUID) = topics(tenant, "route-changed")

    private fun Running.statusEvents(tenant: UUID) = topics(tenant, "connection-status")

    private fun failoverBody(
        target: String,
        entity: String?,
    ): String {
        val ref = entity?.let { ",\"entityRef\":\"$it\"" }.orEmpty()
        return """{"targetAdapterRef":"$target"$ref}"""
    }

    // ---- failoverAdapter (INV-AC-18677) ----

    @Test
    fun `INV-AC-18677 failover is refused for a denied caller and without entityRef, done once for a permitted caller`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val permitted = r.userOf(tenant, setOf("E1"), RouteCaller.ADAPTER_FAILOVER_PERMISSION)
        val denied = r.userOf(tenant, setOf("E1"))
        r.harness.assign(tenant, "E1", r.harness.primary)
        val second = r.harness.secondary.value

        val refused = r.failover(failoverBody(second, "E1"), denied)
        refused.status shouldBe 403
        r.assigned(tenant, "E1") shouldBe r.harness.primary.value

        val invalid = r.failover(failoverBody(second, null), permitted)
        invalid.status shouldBe 400
        invalid.body shouldContain "SRT-001"
        r.assigned(tenant, "E1") shouldBe r.harness.primary.value

        val done = r.failover(failoverBody(second, "E1"), permitted)
        done.status shouldBe 200
        done.body shouldContain second
        r.assigned(tenant, "E1") shouldBe second
        val firstAt = r.assignedAt(tenant, "E1")

        r.failover(failoverBody(second, "E1"), permitted).status shouldBe 200
        r.assigned(tenant, "E1") shouldBe second
        r.assignedAt(tenant, "E1") shouldBe firstAt
    }

    @Test
    fun `INV-AC-18677 the key adapter configure permits no cutover and adapter failover no configuration`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        r.harness.assign(tenant, "E1", r.harness.primary)
        val second = r.harness.secondary.value
        val configureOnly = r.userOf(tenant, setOf("E1"), RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        r.failover(failoverBody(second, "E1"), configureOnly).status shouldBe 403
        val lookAlikes =
            listOf("adapter.failover ", "adapter.Failover", "adapter.failovers", "adapter.failover.x", "adapter.*")
        for (key in lookAlikes) {
            r.failover(failoverBody(second, "E1"), r.userOf(tenant, setOf("E1"), key)).status shouldBe
                403
        }
        r.assigned(tenant, "E1") shouldBe r.harness.primary.value
        val failoverOnly = r.user(tenant, RouteCaller.ADAPTER_FAILOVER_PERMISSION)
        r.setEntities(r.harness.primary.value, listOf("E1"), failoverOnly).status shouldBe 403
        r.testConnection(r.harness.primary.value, failoverOnly).status shouldBe 403
        r.failover(failoverBody(second, "E1"), null).status shouldBe 401
    }

    @Test
    fun `a failover to an adapter that is not registered is 404 and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        r.harness.assign(tenant, "E1", r.harness.primary)
        val user = r.userOf(tenant, setOf("E1"), RouteCaller.ADAPTER_FAILOVER_PERMISSION)
        r.failover(failoverBody("nope", "E1"), user).status shouldBe 404
        r.assigned(tenant, "E1") shouldBe r.harness.primary.value
    }

    // ---- setConnectionEntities ----

    @Test
    fun `setConnectionEntities refuses an entity of another connection 422 SUB-018, nothing recorded or published`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val user = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val (first, second) = r.harness.primary.value to r.harness.secondary.value
        r.setEntities(first, listOf("E1", "E2"), user).status shouldBe 200
        val published = r.routeEvents(tenant).size
        val refused = r.setEntities(second, listOf("E3", "E1"), user)
        refused.status shouldBe 422
        refused.body shouldContain "SUB-018"
        r.storedEntities(tenant, second) shouldBe emptyList()
        r.routeEvents(tenant).size shouldBe published
        // a look-alike ref held by no connection is accepted, and a tenant's own connection may keep its entity
        r.setEntities(second, listOf("e1", "E10"), user).status shouldBe 200
        r.setEntities(first, listOf("E1", "E2", "E4"), user).status shouldBe 200
    }

    @Test
    fun `setConnectionEntities does not see the connection of another tenant holding the same entity ref`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val a = r.tenantWithConnections()
        val b = r.tenantWithConnections()
        val configure = RouteCaller.ADAPTER_CONFIGURE_PERMISSION
        r.setEntities(r.harness.primary.value, listOf("E1"), r.user(a, configure)).status shouldBe 200
        r.setEntities(r.harness.secondary.value, listOf("E1"), r.user(b, configure)).status shouldBe 200
    }

    @Test
    fun `setConnectionEntities refuses the removal of an Observe entity 422 SUB-019, nothing recorded or published`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val user = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val first = r.harness.primary.value
        r.setEntities(first, listOf("E1"), user).status shouldBe 200
        val published = r.routeEvents(tenant).size
        // E1 has no issuance-mode replica row: the guard fails closed
        val refused = r.setEntities(first, emptyList(), user)
        refused.status shouldBe 422
        refused.body shouldContain "SUB-019"
        r.storedEntities(tenant, first) shouldContainExactly listOf("E1")
        r.routeEvents(tenant).size shouldBe published
    }

    @Test
    fun `setConnectionEntities with the same Idempotency-Key returns the original outcome and publishes once`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val user = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val first = r.harness.primary.value
        val key = UUID.randomUUID().toString()
        val one = r.setEntities(first, listOf("E1", "E2"), user, key)
        one.status shouldBe 200
        r.routeEvents(tenant).size shouldBe 1
        val again = r.setEntities(first, listOf("E1", "E2"), user, key)
        again.status shouldBe 200
        again.body shouldBe one.body
        r.routeEvents(tenant).size shouldBe 1
        // the same key with another body is not a replay
        r.setEntities(first, listOf("E1"), user, key).status shouldBe 422
        r.storedEntities(tenant, first).sorted() shouldContainExactly listOf("E1", "E2")
    }

    @Test
    fun `setConnectionEntities refuses a list with a repeated entity ref by validation, nothing recorded`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val user = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val first = r.harness.primary.value
        val refused = r.setEntities(first, listOf("E1", "E2", "E1"), user)
        refused.status shouldBe 400
        refused.body shouldContain "SRT-001"
        r.storedEntities(tenant, first) shouldBe emptyList()
        r.routeEvents(tenant).size shouldBe 0
        r.setEntities(first, listOf("E1", "e1"), user).status shouldBe 200
    }

    @Test
    fun `setConnectionEntities by a caller without adapter configure is refused 403, nothing recorded`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val first = r.harness.primary.value
        r.setEntities(first, listOf("E1"), r.user(tenant)).status shouldBe 403
        val granted = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        r.setEntities("nope", listOf("E1"), granted).status shouldBe 404
        r.storedEntities(tenant, first) shouldBe emptyList()
        r.routeEvents(tenant).size shouldBe 0
    }

    // ---- testConnection ----

    private fun Answer.resultClass() = mapper.readTree(body).get("resultClass").asString()

    private fun Answer.state() = mapper.readTree(body).get("state").asString()

    private fun Answer.detail() =
        mapper
            .readTree(body)
            .get("detail")
            ?.takeIf { !it.isNull }
            ?.asString()

    @Test
    fun `testConnection against the simulator answers each class with a code-only detail`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.breaker.failure-threshold=50", "transport.ap.timeout=4s") { r ->
        val tenant = r.tenantWithConnections()
        val user = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val primary = r.harness.primary.value

        val ok = r.testConnection(primary, user)
        ok.status shouldBe 200
        withClue(ok.body) { ok.resultClass() shouldBe "SUCCESS" }
        ok.state() shouldBe "UP"
        ok.detail() shouldBe null

        ApSimulator.control("/ap/adapters/primary/auth-failure", """{"enabled":true}""")
        val refused = r.testConnection(primary, user)
        refused.resultClass() shouldBe "AUTHENTICATION_REFUSED"
        refused.detail() shouldBe "AP_AUTH_REFUSED"
        ApSimulator.reset()

        val html = """{"status":200,"body":"<html>not the page</html>","contentType":"text/html"}"""
        ApSimulator.control("/faults/error", html)
        val unexpected = r.testConnection(primary, user)
        unexpected.resultClass() shouldBe "UNEXPECTED_RESPONSE"
        unexpected.body shouldNotContain "not the page"
        ApSimulator.control("/faults/error", """{"status":503}""")
        r.testConnection(primary, user).detail() shouldBe "AP_STATUS_503"
        ApSimulator.reset()

        ApSimulator.control("/faults/latency", """{"delayMs":6000}""")
        val before = ApSimulator.callCount()
        val slow = r.testConnection(primary, user)
        slow.resultClass() shouldBe "UNREACHABLE"
        slow.detail() shouldBe "AP_TIMEOUT"
        // the simulator still handles the request the client gave up on: wait for it before the next reset
        ApSimulator.awaitCalls(before + 1)
        ApSimulator.reset()
    }

    @Test
    fun `testConnection of an AP that does not answer is UNREACHABLE`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.sandbox-endpoint=http://localhost:1", "transport.ap.breaker.failure-threshold=50") { r ->
        val tenant = r.tenantWithConnections()
        val answer = r.testConnection(r.harness.primary.value, r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION))
        answer.status shouldBe 200
        answer.resultClass() shouldBe "UNREACHABLE"
        answer.detail() shouldBe "AP_UNREACHABLE"
    }

    @Test
    fun `testConnection reports the breaker state and publishes ConnectionStatusChanged only when the state changes`(
        @TempDir dir: Path,
    ) = run(
        dir,
        "transport.ap.breaker.failure-threshold=2",
        "transport.ap.breaker.open-for=2s",
        "transport.ap.breaker.half-open-successes=2",
    ) { r ->
        val tenant = r.tenantWithConnections()
        val user = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val primary = r.harness.primary.value
        r.testConnection(primary, user).state() shouldBe "UP"
        r.statusEvents(tenant).size shouldBe 0

        ApSimulator.control("/ap/adapters/primary/auth-failure", """{"enabled":true}""")
        r.testConnection(primary, user).state() shouldBe "UP"
        r.statusEvents(tenant).size shouldBe 0
        val down = r.testConnection(primary, user)
        withClue(down.body) { down.state() shouldBe "DOWN" }
        r.statusEvents(tenant).size shouldBe 1
        r.statusEvents(tenant).single() shouldContain "down"

        // open: the AP is not called, the state is unchanged, nothing is published
        val calls = ApSimulator.callCount()
        val blocked = r.testConnection(primary, user)
        blocked.state() shouldBe "DOWN"
        blocked.detail() shouldBe "CIRCUIT_OPEN"
        ApSimulator.callCount() shouldBe calls
        r.statusEvents(tenant).size shouldBe 1

        // half-open after the wait: one success is DEGRADED, the second closes the breaker
        ApSimulator.control("/ap/adapters/primary/auth-failure", """{"enabled":false}""")
        Thread.sleep(OPEN_WAIT_MILLIS)
        r.testConnection(primary, user).state() shouldBe "DEGRADED"
        r.statusEvents(tenant).size shouldBe 2
        r.testConnection(primary, user).state() shouldBe "UP"
        r.statusEvents(tenant).size shouldBe 3
        r.testConnection(primary, user).state() shouldBe "UP"
        r.statusEvents(tenant).size shouldBe 3
    }

    @Test
    fun `testConnection is refused without adapter configure or for an unknown connection, the AP is not called`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        r.testConnection(r.harness.primary.value, r.user(tenant)).status shouldBe 403
        r.testConnection("nope", r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)).status shouldBe 404
        ApSimulator.callCount() shouldBe 0
    }

    private companion object {
        /** The entity set of a caller: the body entity refs of `x-entity-ref` operations must lie in it (API-24). */
        val ENTITIES = setOf("E1", "E2", "E3", "E4", "E10", "e1")
        const val UNIQUE = 12
        const val OPEN_WAIT_MILLIS = 2_300L
        const val ADAPTERS = "/internal/v1/transport/adapters"
        const val CONNECTIONS = "/internal/v1/transport/connections"
    }
}
