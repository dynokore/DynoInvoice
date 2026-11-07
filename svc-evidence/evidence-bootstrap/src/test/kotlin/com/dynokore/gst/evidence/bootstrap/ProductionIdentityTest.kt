package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.domain.retention.RetentionTier
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-12597 (INV-T-12601, INV-Q-12588 A): the production wiring of evidence-svc binds the `IdentityProvider` from
 * required properties and decides `setPolicy` for VP-43 (`vp-43.set`) through `AuthorityReplica.decide`; the token is
 * signed by a test key whose key set the test serves at `jwk-set-uri`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionIdentityTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private val versions = AtomicLong()
    private lateinit var key: RSAKey
    private lateinit var otherKey: RSAKey
    private lateinit var jwks: HttpServer
    private lateinit var issuer: String
    private lateinit var jwkSetUri: String

    @BeforeAll
    fun startKeyServer() {
        key = RSAKeyGenerator(2048).keyID("k1").generate()
        otherKey = RSAKeyGenerator(2048).keyID("k1").generate() // the same kid, another key: a forged signature
        jwks = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
        jwks.createContext("/jwks") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        jwks.start()
        val base = "http://127.0.0.1:${jwks.address.port}"
        issuer = "$base/realms/test"
        jwkSetUri = "$base/jwks"
    }

    @AfterAll
    fun stopKeyServer() = jwks.stop(0)

    // one defaulted parameter per claim or key a case varies
    @Suppress("LongParameterList")
    private fun token(
        sub: String?,
        tenant: TenantId,
        aud: String = AUDIENCE,
        iss: String = issuer,
        expires: Instant = Instant.now().plusSeconds(300),
        signWith: RSAKey = key,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(sub)
                .issuer(iss)
                .audience(aud)
                .claim("tenant_id", tenant.toString())
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expires))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims)
        jwt.sign(RSASSASigner(signWith))
        return jwt.serialize()
    }

    private fun grant(
        replica: AuthorityReplica,
        tenant: TenantId,
        principal: String,
        role: String,
    ) {
        val at = versions.incrementAndGet()
        val grant = RoleGrant(role, GrantScope.TENANT, tenant.toString(), Instant.EPOCH)
        val record = AuthorityRecord.RoleGrantRecord("grant-$principal-$role", RoleGrantState.ACTIVE, grant)
        replica.accept(AuthorityChanged(tenant, principal, at, record))
        replica.onSnapshotComplete(at)
    }

    /**
     * The Gradle test task supplies the installation values as system properties, which outrank the default properties
     * [EvidenceContext.startServlet] sets; this test sets its own for the call and restores the others.
     */
    private fun <T> withIdentity(block: () -> T): T {
        val values =
            mapOf(
                "evidence.identity-provider.issuer-uri" to issuer,
                "evidence.identity-provider.jwk-set-uri" to jwkSetUri,
                "service-runtime.end-user-identity.audience" to AUDIENCE,
            )
        val saved = values.keys.associateWith { System.getProperty(it) }
        values.forEach { (name, value) -> System.setProperty(name, value) }
        try {
            return block()
        } finally {
            saved.forEach { (name, old) ->
                if (old ==
                    null
                ) {
                    System.clearProperty(name)
                } else {
                    System.setProperty(name, old)
                }
            }
        }
    }

    private fun production(dir: Path): RetentionService = withIdentity { start(dir) }

    private fun start(dir: Path): RetentionService {
        val context: ConfigurableApplicationContext =
            EvidenceContext.startServlet(
                dir,
                listOf(WorkloadTokensOnly::class.java),
                arrayOf(
                    "evidence.identity-provider.issuer-uri=$issuer",
                    "evidence.identity-provider.jwk-set-uri=$jwkSetUri",
                    "service-runtime.end-user-identity.audience=$AUDIENCE",
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                ),
                ProductionIdentityTest::class.java.classLoader,
            )
        return RetentionService(context)
    }

    private fun setPolicy(
        s: RetentionService,
        tenant: TenantId,
        token: String?,
    ): Pair<Int, String?> {
        val node = mapper.createObjectNode()
        node.put("scopeLevel", "TENANT")
        node.put("scopeRef", tenant.toString())
        val option = node.putArray("selected").addObject()
        option.put("option", "LONGER")
        val value = option.putObject("value")
        RetentionTier.entries.forEach { value.put(it.property, if (it == RetentionTier.entries.first()) 7 else 5) }
        node.put("effectiveDate", RetentionService.today().toString())
        node.put("reason", "HGB 257 obligation of the parent")
        val port = s.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/v1/policies/VP-43"))
                .header("X-Workload-Token", "token-console-bff")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(node)))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body()).get("code")?.asString()
    }

    private fun tiers(
        s: RetentionService,
        tenant: TenantId,
    ) = s.years(tenant, "ENT-A")

    @Test
    fun `INV-AC-12597 setPolicy of VP-43 is accepted only for a verified token of a principal holding vp-43-set`(
        @TempDir dir: Path,
    ) {
        production(dir).use { s ->
            val tenant = s.newTenant()
            val replica = s.bean(AuthorityReplica::class.java)
            grant(replica, tenant, "p1", "tenant.admin")
            grant(replica, tenant, "p2", "tax.approver")
            val before = tiers(s, tenant)
            before shouldBe listOf(5, 5, 5)

            // every refusal first: nothing is stored, the tiers stay at 5
            val refused =
                mapOf(
                    "P2 without vp-43.set" to (token("p2", tenant) to 403),
                    "P3 without a grant" to (token("p3", tenant) to 403),
                    "another audience" to (token("p1", tenant, aud = "other-svc") to 401),
                    "another issuer" to (token("p1", tenant, iss = "$issuer-other") to 401),
                    "an expired token" to (token("p1", tenant, expires = Instant.now().minusSeconds(120)) to 401),
                    "a forged signature" to (token("p1", tenant, signWith = otherKey) to 401),
                    "no token" to (null to 401),
                )
            for ((case, expected) in refused) {
                val (status, code) = setPolicy(s, tenant, expected.first)
                "$case: $status $code" shouldBe
                    "$case: ${expected.second} ${if (expected.second == 401) "SRT-003" else "SRT-004"}"
                s.stored(tenant) shouldBe 0
                tiers(s, tenant) shouldBe before
            }

            // P1 is accepted and the stored value equals the sent value
            setPolicy(s, tenant, token("p1", tenant)).first shouldBe 200
            s.stored(tenant) shouldBe 1
            tiers(s, tenant) shouldBe listOf(7, 5, 5)

            // the audit entry of the accepted call carries the acting user id: the sub of the token (INV-Q-10647)
            auditActors(tenant) shouldBe listOf("p1")
        }
    }

    /** The `actor` of every `AuditEntryRecorded` row of [tenant] in `evidence.outbox`, oldest first. */
    private fun auditActors(tenant: TenantId): List<String> =
        EvidenceDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select data->>'actor' from evidence.outbox where tenant_id = '${tenant.value}' " +
                            "and type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' order by seq",
                    ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    private fun startupFailure(
        dir: Path,
        vararg properties: String,
    ): String {
        var failure: Throwable? = null
        EvidenceContext
            .offline(dir)
            // the Gradle test task's system properties would otherwise supply the issuer and the audience
            .withInitializer { it.environment.propertySources.remove("systemProperties") }
            .withPropertyValues(*properties)
            .run { failure = it.startupFailure }
        val thrown = checkNotNull(failure) { "the context started" }
        return generateSequence(thrown) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @Test
    fun `INV-AC-12597 the context does not start when the issuer is missing blank or written as a look-alike`(
        @TempDir dir: Path,
    ) {
        val audience = "service-runtime.end-user-identity.audience=$AUDIENCE"
        startupFailure(dir, audience) shouldContain "evidence.identity-provider.issuer-uri"
        startupFailure(dir, audience, "evidence.identity-provider.issuer-uri=") shouldContain
            "evidence.identity-provider.issuer-uri"
        startupFailure(dir, audience, "evidence.identity-provider.issuer-uri=   ") shouldContain
            "evidence.identity-provider.issuer-uri"
        startupFailure(
            dir,
            audience,
            "evidence.identity-providers.issuer-uri=https://issuer.invalid/realms/test",
        ) shouldContain
            "evidence.identity-provider.issuer-uri"
    }

    @Test
    fun `INV-AC-12597 the context does not start when the audience is missing`(
        @TempDir dir: Path,
    ) {
        startupFailure(dir, "evidence.identity-provider.issuer-uri=https://issuer.invalid/realms/test") shouldContain
            "service-runtime.end-user-identity.audience"
    }

    @Test
    fun `INV-AC-12597 no main class implements EndUserPrincipalResolver PolicyRoleResolver or SetterPermission`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        val modules = listOf("evidence-bootstrap", "evidence-adapters", "evidence-application", "evidence-domain")
        val files =
            modules.flatMap { module ->
                val main = root.resolve(module).resolve("src/main")
                check(Files.isDirectory(main)) { "$main is missing" }
                Files.walk(main).use { walk ->
                    walk.filter { it.toString().endsWith(".kt") }.map { it.toString() to Files.readString(it) }.toList()
                }
            }
        files.isNotEmpty() shouldBe true
        findings(files).shouldBeEmpty()
        // probes: an implementation, a lambda and an object expression of each port are found
        for (port in PORTS) {
            findings(listOf("a" to "class A : $port { }")) shouldBe listOf("a")
            findings(listOf("a" to "val r = $port { _ -> null }")) shouldBe listOf("a")
            findings(listOf("a" to "val o = object : $port { }")) shouldBe listOf("a")
        }
        findings(listOf("a" to "// EndUserPrincipalResolver\nclass B")) shouldBe emptyList()
    }

    private fun findings(files: List<Pair<String, String>>): List<String> {
        val ports = Regex("""\b(${PORTS.joinToString("|")})\b""")
        return files.filter { (_, text) -> ports.containsMatchIn(stripComments(text)) }.map { it.first }
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")

    private companion object {
        const val AUDIENCE = "evidence-svc"
        val PORTS = listOf("EndUserPrincipalResolver", "PolicyRoleResolver", "SetterPermission")
    }
}
