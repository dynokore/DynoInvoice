package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.transport.application.ApplyEntityProfileChanged
import com.dynokore.gst.transport.application.EntityRemovalGuard
import com.dynokore.gst.transport.application.ObserveGuardRefused
import com.dynokore.gst.transport.domain.EntityNode
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

private const val BFF_SUBJECT = "system:serviceaccount:platform:console-bff"
private const val BFF_TOKEN = "W-bff"

/**
 * The acknowledgement-route operations over real HTTP and a real database (INV-REQ-1310; INV-AC-2297, INV-AC-9490):
 * the full TPL-18 context, the runtime's workload and sync-edge filters in front of the generated controller, the
 * route store, the outbox and the issuance-mode replica of the bound tenant.
 */
class AcknowledgementRouteBoundaryTest {
    private val http = HttpClient.newHttpClient()

    private class Running(
        val context: ConfigurableApplicationContext,
        val harness: Harness,
        val port: Int,
    ) {
        val authority: AuthorityReplica =
            context.getBean(AuthorityReplica::class.java).also { it.onSnapshotComplete(0) }
        private var sequence = 0L

        /** A tenant with two connections, a user holding `vp-49.set` (or not) and the Authorization value. */
        fun user(
            tenant: UUID,
            granted: Boolean = true,
        ): String {
            val header = TestPrincipals.register(tenant)
            if (granted) {
                val since = Clock.systemUTC().instant().minusSeconds(60)
                val grant = Grant(AdministrationPermissions.SET_ROUTE, ScopeLevel.TENANT, "x", since)
                val record = AuthorityRecord.GrantRecord("tenant-wide", listOf(grant))
                authority.accept(AuthorityChanged(TenantId(tenant), TestPrincipals.actorOf(header), ++sequence, record))
            }
            return header
        }
    }

    private object AdministrationPermissions {
        const val SET_ROUTE = com.dynokore.gst.transport.adapters.http.RouteCaller.SET_ROUTE_PERMISSION
    }

    private fun run(
        dir: Path,
        block: (Running) -> Unit,
    ) {
        TransportContext.secrets(dir)
        val primary =
            com.dynokore.gst.transport.domain
                .AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
        val secondary =
            com.dynokore.gst.transport.domain
                .AdapterId("s-${UUID.randomUUID().toString().take(UNIQUE)}")
        val properties =
            TransportContext.propertyValues(dir, com.dynokore.gst.transport.adapters.TransportDb.jdbcUrl) +
                listOf(
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
                .run("--server.port=0", "--management.server.port=0")
        try {
            val port = checkNotNull((context as WebServerApplicationContext).webServer).port
            block(Running(context, Harness(context, primary, secondary), port))
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
        connection: String,
        body: String?,
        authorization: String?,
        workload: String? = BFF_TOKEN,
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$CONNECTIONS/$connection/acknowledgement-route"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        workload?.let { request.header(WorkloadToken.HEADER_NAME, it) }
        authorization?.let { request.header("Authorization", it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun Running.elect(
        connection: String,
        route: String,
        user: String?,
    ) = send("PUT", connection, """{"acknowledgementRoute":"$route"}""", user)

    private fun Running.clear(
        connection: String,
        user: String?,
    ) = send("DELETE", connection, null, user)

    /** Two connections of a new tenant, `entities` listed on the first. */
    private fun Running.connections(entities: List<String> = emptyList()): Triple<UUID, String, String> {
        val tenant = UUID.randomUUID()
        harness.connect(tenant, harness.primary, "10")
        harness.connect(tenant, harness.secondary, "10")
        setEntities(tenant, harness.primary.value, entities)
        return Triple(tenant, harness.primary.value, harness.secondary.value)
    }

    private fun Running.setEntities(
        tenant: UUID,
        connection: String,
        entities: List<String>,
    ) = harness.asTenant(tenant) {
        harness
            .dsl
            .update(AP_CONNECTION)
            .set(AP_CONNECTION.ENTITY_REFS, entities.toTypedArray())
            .where(AP_CONNECTION.TENANT_ID.eq(tenant))
            .and(AP_CONNECTION.ADAPTER_ID.eq(connection))
            .execute()
    }

    /** An EntityProfileChanged of the entity, applied to the replica as the consumer does. */
    private fun Running.profile(
        tenant: UUID,
        entity: String,
        version: Long,
        vararg modes: Pair<String, LocalDate>,
    ) = harness.asTenant(tenant) {
        context
            .getBean(ApplyEntityProfileChanged::class.java)
            .apply(
                TenantRef(tenant.toString()),
                EntityRef(entity),
                EntityNode(version, modes.map { IssuanceModeEntry(it.first, it.second) }),
            )
    }

    private val past: LocalDate = LocalDate.parse("2020-01-01")

    private fun Running.storedRoute(
        tenant: UUID,
        connection: String,
    ): String? =
        harness.asTenant(tenant) {
            harness
                .dsl
                .select(AP_CONNECTION.ACKNOWLEDGEMENT_ROUTE)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(tenant))
                .and(AP_CONNECTION.ADAPTER_ID.eq(connection))
                .fetchOne()
                ?.value1()
        }

    /** The payloads of the `AccessPointRouteChanged` rows of the tenant, in write order. */
    private fun Running.published(tenant: UUID): List<String> =
        harness.asTenant(tenant) {
            harness
                .dsl
                .select(Outbox.OUTBOX.TOPIC, Outbox.OUTBOX.DATA)
                .from(Outbox.OUTBOX)
                .orderBy(Outbox.OUTBOX.SEQ)
                .fetch()
                .filter { it.value1() == ROUTE_TOPIC }
                .map { checkNotNull(it.value2()).data() }
        }

    @Test
    fun `INV-AC-9490 a fourth route value is a 400 SRT-001, the stored route and the published events are unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, second) = r.connections()
        val user = r.user(tenant)
        r.elect(first, "AP_CALLBACK", user).status shouldBe 200
        val before = r.published(tenant)
        for (value in listOf("FOURTH_VALUE", "ap_callback", "AP_CALLBACK ", "")) {
            val answer = r.elect(second, value, user)
            answer.status shouldBe 400
            answer.body shouldContain "SRT-001"
        }
        r.send("PUT", second, """{}""", user).status shouldBe 400
        r.storedRoute(tenant, second).shouldBeNull()
        r.storedRoute(tenant, first) shouldBe "AP_CALLBACK"
        r.published(tenant) shouldBe before
    }

    @Test
    fun `INV-AC-9490 clearing is refused 422 SUB-023 naming the connection while an entity is Observe, accepted after`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections(listOf("E-1"))
        val user = r.user(tenant)
        r.elect(first, "AP_CALLBACK", user).status shouldBe 200
        r.profile(tenant, "E-1", 1, "OBSERVE" to past)
        val before = r.published(tenant)
        val refused = r.clear(first, user)
        refused.status shouldBe 422
        refused.body shouldContain "SUB-023"
        refused.body shouldContain first
        r.storedRoute(tenant, first) shouldBe "AP_CALLBACK"
        r.published(tenant) shouldBe before
        r.profile(tenant, "E-1", 2, "RELAY" to past)
        val accepted = r.clear(first, user)
        accepted.status shouldBe 200
        accepted.body shouldNotContain "acknowledgementRoute"
        r.storedRoute(tenant, first).shouldBeNull()
        r.published(tenant).size shouldBe before.size + 1
    }

    @Test
    fun `INV-AC-9490 an Observe entity of a different connection does not stop the clearing`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, second) = r.connections(listOf("E-1"))
        r.setEntities(tenant, second, listOf("E-2"))
        val user = r.user(tenant)
        r.elect(first, "POLLING", user).status shouldBe 200
        r.profile(tenant, "E-1", 1, "RELAY" to past)
        r.profile(tenant, "E-2", 1, "OBSERVE" to past)
        r.clear(first, user).status shouldBe 200
        r.storedRoute(tenant, first).shouldBeNull()
    }

    @Test
    fun `INV-AC-9490 an entity of the connection with no replica row refuses the clearing (fail closed)`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections(listOf("E-1", "E-2"))
        val user = r.user(tenant)
        r.elect(first, "REPORT_IMPORT", user).status shouldBe 200
        r.profile(tenant, "E-1", 1, "RELAY" to past)
        val refused = r.clear(first, user)
        refused.status shouldBe 422
        refused.body shouldContain "SUB-023"
        r.storedRoute(tenant, first) shouldBe "REPORT_IMPORT"
    }

    @Test
    fun `INV-AC-9490 an EntityProfileChanged older than the stored one is ignored, so the Observe entity still refuses`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections(listOf("E-1"))
        val user = r.user(tenant)
        r.elect(first, "AP_CALLBACK", user).status shouldBe 200
        r.profile(tenant, "E-1", 5, "OBSERVE" to past)
        r.profile(tenant, "E-1", 4, "RELAY" to past)
        r.clear(first, user).status shouldBe 422
        r.profile(tenant, "E-1", 5, "RELAY" to past)
        r.clear(first, user).status shouldBe 422
        r.storedRoute(tenant, first) shouldBe "AP_CALLBACK"
    }

    @Test
    fun `INV-AC-9490 the removal of an Observe entity is refused SUB-019 and of a non-Observe entity accepted`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections(listOf("E-1", "E-2"))
        r.profile(tenant, "E-1", 1, "OBSERVE" to past)
        r.profile(tenant, "E-2", 1, "RELAY" to past)
        val guard = r.context.getBean(EntityRemovalGuard::class.java)
        r.harness.asTenant(tenant) {
            val ref = TenantRef(tenant.toString())
            val refused = shouldThrow<ObserveGuardRefused> { guard.check(ref, first, EntityRef("E-1")) }
            refused.code shouldBe "SUB-019"
            refused.message shouldContain first
            guard.check(ref, first, EntityRef("E-2"))
        }
    }

    @Test
    fun `INV-AC-9490 three changes publish versions n, n+1 and n+2, a repeat or a clear route publishes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections(listOf("E-1"))
        val user = r.user(tenant)
        r.clear(first, user).status shouldBe 200
        r.published(tenant).size shouldBe 0
        r.elect(first, "AP_CALLBACK", user).status shouldBe 200
        r.elect(first, "AP_CALLBACK", user).status shouldBe 200
        r.elect(first, "POLLING", user).status shouldBe 200
        r.profile(tenant, "E-1", 1, "RELAY" to past)
        r.clear(first, user).status shouldBe 200
        val payloads = r.published(tenant)
        payloads.size shouldBe 3
        val versions = payloads.map { Regex(""""version":\s*(\d+)""").find(it)!!.groupValues[1].toInt() }
        versions[1] shouldBe versions[0] + 1
        versions[2] shouldBe versions[0] + 2
        val routeOf = Regex(""""acknowledgementRoute":\s*"(\w+)"""")
        val routes = payloads.map { routeOf.find(it)?.groupValues?.get(1) }
        routes shouldContainExactly listOf("AP_CALLBACK", "POLLING", null)
        payloads.forEach { it shouldContain "\"entityRefs\"" }
    }

    @Test
    fun `INV-AC-2297 an election publishes the route so that Observe can be set on the connection, none before`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections(listOf("E-1"))
        val user = r.user(tenant)
        r.published(tenant).size shouldBe 0
        val answer = r.elect(first, "AP_CALLBACK", user)
        answer.status shouldBe 200
        answer.body shouldContain "AP_CALLBACK"
        val payload = r.published(tenant).single()
        payload shouldContain "AP_CALLBACK"
        payload shouldContain first
    }

    @Test
    fun `INV-AC-2297 a foreign, case-changed or made-up ref is 404 SRT-005, nothing recorded`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections()
        val (other, otherFirst, _) = r.connections()
        val user = r.user(tenant)
        for (ref in listOf(otherFirst.replace(first, "x"), first.uppercase(), "nope", "primary")) {
            if (ref == first) continue
            r.elect(ref, "POLLING", user).status shouldBe 404
            r.clear(ref, user).status shouldBe 404
        }
        r.storedRoute(other, otherFirst).shouldBeNull()
        r.published(tenant).size shouldBe 0
        r.published(other).size shouldBe 0
    }

    @Test
    fun `INV-AC-2297 a caller without the vp-49 grant, without a token or workload token is refused, nothing recorded`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, first, _) = r.connections()
        val ungranted = r.user(tenant, granted = false)
        r.elect(first, "POLLING", ungranted).status shouldBe 403
        r.clear(first, ungranted).status shouldBe 403
        r.elect(first, "POLLING", null).status shouldBe 401
        val body = """{"acknowledgementRoute":"POLLING"}"""
        r.send("PUT", first, body, r.user(tenant), workload = null).status shouldBe 401
        r.storedRoute(tenant, first).shouldBeNull()
        r.published(tenant).size shouldBe 0
    }

    @Test
    fun `INV-AC-9490 the context holds the EntityProfileChanged inbox consumer and the catalogue lists its type`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.context.getBeansOfType(JdbcInboxConsumer::class.java).size shouldBe 1
        r.context
            .getBean(com.dynokore.platform.outboxandqueue.EventCatalogue::class.java)
            .find(TransportEvents.ENTITY_PROFILE_TYPE)!!
            .let { it.topic shouldBe TransportEvents.ENTITY_PROFILE_TOPIC }
    }

    private companion object {
        const val UNIQUE = 12
        const val CONNECTIONS = "/internal/v1/transport/connections"
        const val ROUTE_TOPIC = "gst.transport.access-point-route-changed.v1"
    }
}
