package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.gst.transport.adapters.http.RouteCaller
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApCredentialRotation.Companion.AP_CREDENTIAL_ROTATION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApCredentialState.Companion.AP_CREDENTIAL_STATE
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.serviceruntime.connection.CircuitBreakerState
import com.dynokore.platform.serviceruntime.connection.ConnectionFailure
import com.dynokore.platform.serviceruntime.connection.ConnectionRecord
import com.dynokore.platform.serviceruntime.connection.ConnectionStatusReporter
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val BFF_SUBJECT = "system:serviceaccount:platform:console-bff"
private const val BFF_TOKEN = "W-bff"
private const val SECRET_VALUE = "s3cret-value-never-stored"

/** The connection records handed to the shared status reporter: (adapter name, credential reference). */
internal object ReportedCredentials {
    val records = CopyOnWriteArrayList<Pair<String, String>>()
}

@Configuration(proxyBeanMethods = false)
internal class RecordingReporterConfig {
    @Bean
    fun recordingReporter(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is ConnectionStatusReporter) {
                    object : ConnectionStatusReporter {
                        override fun report(
                            connection: ConnectionRecord,
                            breaker: CircuitBreakerState,
                            lastFailure: ConnectionFailure?,
                        ): Boolean {
                            ReportedCredentials.records += connection.name to connection.credential.value
                            return bean.report(connection, breaker, lastFailure)
                        }
                    }
                } else {
                    bean
                }
        }
}

/**
 * `rotateCredential` over real HTTP, a real database and the real `SecretStore` over a mounted directory (the vault
 * double: a file per reference) (INV-T-18676; INV-REQ-837). The AuthorityReplica is the real one, fed per test with the
 * grants of the caller.
 */
class CredentialRotationHttpTest {
    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun reset() {
        ApSimulator.reset()
        ReportedCredentials.records.clear()
    }

    private class Running(
        val context: ConfigurableApplicationContext,
        val harness: Harness,
        val port: Int,
        val vault: Path,
    ) {
        val authority: AuthorityReplica = context.getBean(AuthorityReplica::class.java)
        private var sequence = 0L

        fun user(
            tenant: UUID,
            vararg keys: String,
        ): String {
            val header = TestPrincipals.register(tenant)
            if (keys.isNotEmpty()) {
                val since = Clock.systemUTC().instant().minusSeconds(60)
                val grants = keys.map { Grant(it, ScopeLevel.TENANT, "x", since) }
                val record = AuthorityRecord.GrantRecord("tenant-wide", grants)
                authority.accept(AuthorityChanged(TenantId(tenant), TestPrincipals.actorOf(header), ++sequence, record))
            }
            return header
        }

        /** The vault holds [ref] (a reference that is a path under the mount root) with [SECRET_VALUE]. */
        fun hold(ref: String) {
            val file = vault.resolve(ref)
            Files.createDirectories(file.parent)
            Files.writeString(file, SECRET_VALUE)
        }
    }

    private fun Running.expectRefusal(
        answer: Answer,
        status: Int,
        code: String,
    ) {
        // each code is a member of the generated catalogue, not a literal the catalogue lacks
        com.dynokore.gst.catalogue.ErrorCode.entries
            .map { it.code }
            .contains(code) shouldBe true
        withClue(answer.body) { answer.status shouldBe status }
        answer.body shouldContain "/$code\""
    }

    private class Answer(
        val status: Int,
        val body: String,
    )

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
            SpringApplicationBuilder(
                TransportApplication::class.java,
                CallerTestConfig::class.java,
                RecordingReporterConfig::class.java,
            ).web(WebApplicationType.SERVLET)
                .properties(*properties.toTypedArray())
                .run("--server.port=0", "--management.server.port=0", *overrides.map { "--$it" }.toTypedArray())
        try {
            val port = checkNotNull((context as WebServerApplicationContext).webServer).port
            val running = Running(context, Harness(context, primary, secondary), port, dir)
            running.authority.onSnapshotComplete(0)
            block(running)
        } finally {
            context.close()
        }
    }

    private fun Running.post(
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
                .POST(body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        authorization?.let { request.header("Authorization", it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun Running.rotate(
        adapter: String,
        credentialId: String,
        user: String?,
        key: String = UUID.randomUUID().toString(),
    ) = post("$ADAPTERS/$adapter/credential-rotations", """{"credentialId":"$credentialId"}""", user, key)

    private fun Running.tenantWithConnections(): UUID {
        val tenant = UUID.randomUUID()
        harness.connect(tenant, harness.primary, "10")
        harness.connect(tenant, harness.secondary, "10")
        return tenant
    }

    /** (current, previous) of the tenant's state row; the initial reference with no previous when it has none. */
    private fun Running.state(
        tenant: UUID,
        adapter: String,
    ): Pair<String, String?> =
        harness.asTenant(tenant) {
            val row =
                harness
                    .dsl
                    .selectFrom(AP_CREDENTIAL_STATE)
                    .where(AP_CREDENTIAL_STATE.ADAPTER_ID.eq(adapter))
                    .fetchOne()
            row?.let { checkNotNull(it.currentRef) to it.previousRef } ?: ("ap/$adapter" to null)
        }

    private fun Running.rotations(
        tenant: UUID,
        adapter: String,
    ): Int =
        harness.asTenant(tenant) {
            harness.dsl.fetchCount(AP_CREDENTIAL_ROTATION, AP_CREDENTIAL_ROTATION.ADAPTER_ID.eq(adapter))
        }

    private fun Answer.field(name: String) = mapper.readTree(body).get(name)

    @Test
    fun `INV-AC-18678 a permitted caller rotates once, a repeat key returns the original, a denied one changes nothing`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.rotation-overlap=24h") { r ->
        val tenant = r.tenantWithConnections()
        val primary = r.harness.primary.value
        val permitted = r.user(tenant, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)
        r.hold("vault/cred-c2")
        r.state(tenant, primary) shouldBe ("ap/$primary" to null)

        val key = UUID.randomUUID().toString()
        val first = r.rotate(primary, "vault/cred-c2", permitted, key)
        first.status shouldBe 200
        first.field("adapterRef").asString() shouldBe primary
        first.field("credentialId").asString() shouldBe "vault/cred-c2"
        first.field("overlapActive").asBoolean() shouldBe true
        first.field("rotatedAt").isNull shouldBe false
        r.state(tenant, primary) shouldBe ("vault/cred-c2" to "ap/$primary")
        r.rotations(tenant, primary) shouldBe 1

        val again = r.rotate(primary, "vault/cred-c2", permitted, key)
        again.status shouldBe 200
        again.body shouldBe first.body
        // the same reference under another key returns the original outcome and starts no second window
        val other = r.rotate(primary, "vault/cred-c2", permitted)
        other.status shouldBe 200
        other.field("rotatedAt").asString() shouldBe first.field("rotatedAt").asString()
        r.state(tenant, primary) shouldBe ("vault/cred-c2" to "ap/$primary")
        r.rotations(tenant, primary) shouldBe 1

        // denied: nothing changes
        r.hold("vault/cred-c3")
        r.rotate(primary, "vault/cred-c3", r.user(tenant)).status shouldBe 403
        r.rotate(primary, "vault/cred-c3", null).status shouldBe 401
        r.state(tenant, primary) shouldBe ("vault/cred-c2" to "ap/$primary")
        r.rotations(tenant, primary) shouldBe 1
        // no secret value in the answer or in any column of the two tables
        first.body shouldNotContain SECRET_VALUE
        r.harness.asTenant(tenant) {
            val rows =
                r.harness.dsl
                    .fetch(AP_CREDENTIAL_STATE)
                    .toString() + r.harness.dsl.fetch(AP_CREDENTIAL_ROTATION)
            rows shouldNotContain SECRET_VALUE
        }
    }

    @Test
    fun `INV-AC-18678 the key adapter configure and other look-alikes do not permit the rotation`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.rotation-overlap=24h") { r ->
        val tenant = r.tenantWithConnections()
        val primary = r.harness.primary.value
        r.hold("vault/cred-c2")
        val lookAlikes =
            listOf(
                RouteCaller.ADAPTER_CONFIGURE_PERMISSION,
                RouteCaller.ADAPTER_FAILOVER_PERMISSION,
                RouteCaller.SET_ROUTE_PERMISSION,
                "ap-credential.manage ",
                "ap-credential.Manage",
                "ap-credential.manages",
                "ap-credential.*",
                "ap-credential",
            )
        for (key in lookAlikes) r.rotate(primary, "vault/cred-c2", r.user(tenant, key)).status shouldBe 403
        r.state(tenant, primary) shouldBe ("ap/$primary" to null)
        r.rotations(tenant, primary) shouldBe 0
        // and the key opens no other operation
        val manageOnly = r.user(tenant, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)
        r.post("$ADAPTERS/$primary/connection-tests", null, manageOnly).status shouldBe 403
    }

    @Test
    fun `rotation is refused while the overlap window is not configured and nothing changes`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = r.tenantWithConnections()
        val primary = r.harness.primary.value
        r.hold("vault/cred-c2")
        val answer = r.rotate(primary, "vault/cred-c2", r.user(tenant, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION))
        answer.status shouldBe 500
        answer.body shouldContain "/SRT-010\""
        answer.body shouldNotContain "detail"
        r.state(tenant, primary) shouldBe ("ap/$primary" to null)
        r.rotations(tenant, primary) shouldBe 0
    }

    @Test
    fun `a reference the vault does not hold, the current one or an unknown connection changes nothing`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.rotation-overlap=24h") { r ->
        val tenant = r.tenantWithConnections()
        val primary = r.harness.primary.value
        val user = r.user(tenant, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)
        r.expectRefusal(r.rotate(primary, "vault/absent", user), 422, "SUB-026")
        r.expectRefusal(r.rotate(primary, "ap/$primary", user), 422, "SUB-027")
        r.rotate(primary, " ", user).status shouldBe 400
        r.rotate("nope", "vault/absent", user).status shouldBe 404
        r.state(tenant, primary) shouldBe ("ap/$primary" to null)
        r.rotations(tenant, primary) shouldBe 0
        // a window that is still open refuses a second rotation
        r.hold("vault/cred-c2")
        r.hold("vault/cred-c3")
        r.rotate(primary, "vault/cred-c2", user).status shouldBe 200
        r.expectRefusal(r.rotate(primary, "vault/cred-c3", user), 409, "SUB-028")
        r.state(tenant, primary) shouldBe ("vault/cred-c2" to "ap/$primary")
    }

    @Test
    fun `a tenant with no connection to the adapter cannot rotate its credential`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.rotation-overlap=24h") { r ->
        val primary = r.harness.primary.value
        r.hold("vault/cred-c2")
        val tenant = UUID.randomUUID()
        val stranger = r.user(tenant, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)
        r.rotate(primary, "vault/cred-c2", stranger).status shouldBe 404
        r.state(tenant, primary) shouldBe ("ap/$primary" to null)
        r.rotations(tenant, primary) shouldBe 0
    }

    @Test
    fun `a rotation of one tenant leaves the credential state of another tenant of the same adapter alone`(
        @TempDir dir: Path,
    ) = run(dir, "transport.ap.rotation-overlap=24h") { r ->
        val a = r.tenantWithConnections()
        val b = r.tenantWithConnections()
        val primary = r.harness.primary.value
        r.hold("vault/cred-c2")
        r.rotate(primary, "vault/cred-c2", r.user(a, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)).status shouldBe 200
        r.state(a, primary) shouldBe ("vault/cred-c2" to "ap/$primary")
        r.state(b, primary) shouldBe ("ap/$primary" to null)
        r.rotations(b, primary) shouldBe 0
    }

    @Test
    fun `the connection record carries the current credential reference of its adapter, after a rotation as well`(
        @TempDir dir: Path,
    ) = run(
        dir,
        "transport.ap.rotation-overlap=24h",
        "transport.ap.sandbox-endpoint=http://localhost:1",
        "transport.ap.breaker.failure-threshold=1",
    ) { r ->
        val tenant = r.tenantWithConnections()
        val (primary, secondary) = r.harness.primary.value to r.harness.secondary.value
        val configure = r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)
        val manage = r.user(tenant, RouteCaller.AP_CREDENTIAL_MANAGE_PERMISSION)
        r.hold("vault/cred-c2")

        // before any rotation the record carries the initial reference of the adapter
        r.post("$ADAPTERS/$primary/connection-tests", null, configure).status shouldBe 200
        ReportedCredentials.records.last() shouldBe (primary to r.state(tenant, primary).first)
        ReportedCredentials.records.last().second shouldBe "ap/$primary"

        // after a rotation of the other adapter it carries that adapter's new reference (the breaker of each adapter is
        // its own, so its first failed test is a state change that publishes)
        r.rotate(secondary, "vault/cred-c2", manage).status shouldBe 200
        r.post("$ADAPTERS/$secondary/connection-tests", null, configure).status shouldBe 200
        val reported = ReportedCredentials.records.last()
        reported.first shouldBe secondary
        // equality with the current reference of the row; the previous reference and another adapter's are look-alikes
        reported.second shouldBe r.state(tenant, secondary).first
        reported.second shouldBe "vault/cred-c2"
        (reported.second == "ap/$secondary") shouldBe false
        (reported.second == r.state(tenant, primary).first) shouldBe false
        r.state(tenant, primary).first shouldBe "ap/$primary"
    }

    private companion object {
        const val UNIQUE = 12
        const val ADAPTERS = "/internal/v1/transport/adapters"
    }
}
