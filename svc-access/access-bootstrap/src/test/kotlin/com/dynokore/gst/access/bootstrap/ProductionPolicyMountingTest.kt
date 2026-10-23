package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.JwtEndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * Production wiring of the end-user identity and of the policy commands (INV-T-4938, ruling INV-Q-8183 A): the Spring
 * context of access-bootstrap with no test configuration of the principal source. The `EndUserPrincipalResolver` is the
 * library default built from the `IdentityProvider` binding, whose issuer keys are served by a local JWKS endpoint of
 * this test; the servlet is mounted at `PolicyCommandServlet.MAPPING`; a request without a header and a token the
 * issuer keys do not verify are not admitted and store nothing. The criterion key INV-AC-8561 names the tests that
 * assert its two refusals.
 */
class ProductionPolicyMountingTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val issuerKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private lateinit var jwks: HttpServer
    private lateinit var issuer: String

    private companion object {
        const val ADMIN = "user:tenant-admin"
        const val PERSON = "IDN-TWO"
        const val KID = "access-test-key"
        const val AUDIENCE = "access-svc"
    }

    @BeforeEach
    fun startJwks() {
        val key = issuerKey.public as RSAPublicKey
        val modulus = b64(unsigned(key.modulus.toByteArray()))
        val exponent = b64(unsigned(key.publicExponent.toByteArray()))
        val body = """{"keys":[{"kty":"RSA","kid":"$KID","use":"sig","alg":"RS256","n":"$modulus","e":"$exponent"}]}"""
        jwks = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        jwks.createContext("/jwks") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        jwks.start()
        issuer = "http://127.0.0.1:${jwks.address.port}/issuer"
    }

    @AfterEach
    fun stopJwks() = jwks.stop(0)

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unsigned(bytes: ByteArray) =
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

    private fun token(
        key: java.security.KeyPair,
        subject: String = ADMIN,
    ): String {
        val now = System.currentTimeMillis() / 1000
        val header = b64("""{"alg":"RS256","typ":"JWT","kid":"$KID"}""".toByteArray())
        val claims =
            b64(
                (
                    """{"iss":"$issuer","sub":"$subject","aud":"$AUDIENCE","tenant_id":"$tenant",""" +
                        """"iat":$now,"exp":${now + 300}}"""
                ).toByteArray(),
            )
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(key.private)
                update("$header.$claims".toByteArray())
                sign()
            }
        return "$header.$claims.${b64(signature)}"
    }

    /** The production context: no resolver bean of the test, the issuer and its keys through the binding properties. */
    private fun production(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .withPropertyValues(
                "access.identity-provider.issuer-uri=$issuer",
                "access.identity-provider.jwk-set-uri=http://127.0.0.1:${jwks.address.port}/jwks",
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun setUp(context: AssertableWebApplicationContext) {
        val tenants = context.getBean(TenantContext::class.java)
        val today = LocalDate.now(context.getBean(Clock::class.java).withZone(ZoneOffset.UTC))
        // a fixture: no production path creates the first tenant.admin (INV-Q-8185)
        Seeds.grant(context, tenant, SeedSpec(ADMIN, Role.TENANT_ADMIN))
        Seeds.grant(context, tenant, SeedSpec("user:entity-admin", Role.ENTITY_ADMIN))
        context
            .getBean(StructureReplica::class.java)
            .accept(
                OrganisationStructureChanged(tenant, "ENT-1", StructureChange.CREATED, BoundaryFixture.entityNode()),
            )
        val grants = context.getBean(GrantsController::class.java)
        listOf("tax.approver", "tenant.admin").forEach { role ->
            tenants.bind(tenant, ADMIN) {
                grants.grantRole(
                    UUID.randomUUID(),
                    GrantRequest(
                        GrantRequest.GranteeKind.STAFF,
                        PERSON,
                        role,
                        "entity:ENT-1",
                        today.minusDays(1),
                        today.plusDays(400),
                    ),
                )
            }
        }
    }

    private fun landing(context: AssertableWebApplicationContext): String =
        context.getBean(TenantContext::class.java).bind(tenant, PERSON) {
            context
                .getBean(GrantsController::class.java)
                .resolveLandingScreen()
                .body!!
                .role
        }

    private fun servlet(context: AssertableWebApplicationContext): HttpServlet {
        val registrations = context.getBeansOfType(ServletRegistrationBean::class.java).values
        return registrations.single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }.servlet as HttpServlet
    }

    private fun setPolicy(
        context: AssertableWebApplicationContext,
        authorization: String?,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-73")
        request.contentType = "application/json"
        authorization?.let { request.addHeader("Authorization", it) }
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        // the policy writer refuses an effectiveDate before the business date (Asia/Singapore, INV-Q-2940)
        val today = BusinessDate.today(context.getBean(Clock::class.java))
        request.setContent(
            """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"TENANT_PRECEDENCE","value":["tax.approver","tenant.admin"]}],"effectiveDate":"$today","reason":"precedence"}"""
                .toByteArray(),
        )
        val response = MockHttpServletResponse()
        servlet(context).service(request, response)
        return response
    }

    @Test
    fun `the production context holds the library resolver of the IdentityProvider binding and the servlet`() =
        production { context ->
            val resolvers = context.getBeansOfType(EndUserPrincipalResolver::class.java)
            assertThat(resolvers).hasSize(1)
            assertThat(resolvers.values.single()).isInstanceOf(JwtEndUserPrincipalResolver::class.java)
            assertThat(servlet(context)).isNotNull()
        }

    @Test
    fun `a token the issuer keys verify is admitted by the production resolver and changes the policy`() =
        production { context ->
            setUp(context)
            assertThat(landing(context)).isEqualTo("tenant.admin")
            val response = setPolicy(context, "Bearer ${token(issuerKey)}")
            assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
            assertThat(landing(context)).isEqualTo("tax.approver")
        }

    @Test
    fun `INV-AC-8561 a request without an Authorization header is 401 SRT-003 and stores nothing`() =
        production { context ->
            setUp(context)
            val response = setPolicy(context, null)
            assertThat(response.status).isEqualTo(401)
            assertThat(response.contentAsString).contains("SRT-003")
            assertThat(landing(context)).isEqualTo("tenant.admin")
        }

    @Test
    fun `INV-AC-8561 a token signed by a key the issuer does not publish is not admitted and stores nothing`() =
        production { context ->
            setUp(context)
            val response = setPolicy(context, "Bearer ${token(otherKey)}")
            assertThat(response.status).describedAs(response.contentAsString).matches { it !in 200..299 }
            assertThat(landing(context)).isEqualTo("tenant.admin")
        }

    @Test
    fun `without an IdentityProvider issuer the service does not start and so holds no resolver or servlet`() {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .withPropertyValues("access.identity-provider.issuer-uri=")
            .run { context -> assertThat(context.startupFailure).isNotNull() }
    }

    @Test
    fun `no source file outside src-test implements EndUserPrincipalResolver`() {
        val offenders = ProductionResolverScan.offenders(File("..").absoluteFile.normalize())
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the scan flags a class, an object expression and a lambda implementation and ignores tests and parameters`(
        @TempDir root: Path,
    ) {
        fun write(
            path: String,
            text: String,
        ) = root
            .resolve(path)
            .also { it.parent.toFile().mkdirs() }
            .toFile()
            .writeText(text)
        write("m/src/main/kotlin/A.kt", "class A(\n    val x: Int,\n) : Other, EndUserPrincipalResolver {\n}")
        write("m/src/main/kotlin/B.kt", "val b = object : EndUserPrincipalResolver { }")
        write("m/src/main/kotlin/C.kt", "val c = EndUserPrincipalResolver { null }")
        write(
            "m/src/main/kotlin/D.kt",
            "class D(private val r: EndUserPrincipalResolver) { fun f(r: EndUserPrincipalResolver) {} }",
        )
        write("m/src/test/kotlin/E.kt", "class E : EndUserPrincipalResolver")
        write(
            "m/src/main/kotlin/F.kt",
            "// class F : EndUserPrincipalResolver\n/* object : EndUserPrincipalResolver */",
        )
        val offenders = ProductionResolverScan.offenders(root.toFile()).map { it.name }.sorted()
        assertThat(offenders).containsExactly("A.kt", "B.kt", "C.kt")
    }
}

/** The reviewer's comparison of INV-T-4938 (c): the `.kt` files outside tests that implement the port. */
object ProductionResolverScan {
    private const val PORT = """(?:[\w.]+\.)?EndUserPrincipalResolver"""
    private val implementation =
        Regex(
            listOf(
                """(?s)\b(?:class|object|interface)\b[^{=]*?[:,]\s*$PORT\b""",
                """:\s*$PORT\s*\{""",
                """\bEndUserPrincipalResolver\s*\{""",
            ).joinToString("|"),
        )

    fun offenders(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.invariantSeparatorsPath.let { !it.contains("/src/test/") } }
            .filter { implementation.containsMatchIn(withoutParameters(withoutComments(it.readText()))) }
            .toList()

    /** Parameter lists out (innermost first), so a parameter of the port type is not read as a supertype. */
    private fun withoutParameters(text: String): String {
        var current = text
        while (true) {
            val next = current.replace(Regex("""\([^()]*\)"""), " ")
            if (next == current) return current
            current = next
        }
    }

    private fun withoutComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")
}
