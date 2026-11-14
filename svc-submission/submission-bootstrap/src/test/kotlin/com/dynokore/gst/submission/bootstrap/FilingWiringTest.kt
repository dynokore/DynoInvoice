package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.JooqFilingDueDates
import com.dynokore.gst.submission.adapters.persistence.ReadinessLatestChecks
import com.dynokore.gst.submission.adapters.policy.PolicyGateModes
import com.dynokore.gst.submission.application.filing.AmbiguousDueDateException
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.gst.submission.application.filing.GateModesAt
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.syncedge.BreakGlassDoubles
import com.dynokore.gst.submission.syncedge.BreakGlassTestBeans
import com.dynokore.gst.submission.syncedge.ContractDocument
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.Date

/** P109 wiring of `confirmFiling` (INV-REQ-914, INV-AC-20947): the business zone is configuration, never code. */
class FilingWiringTest {
    /**
     * Only the key and the zone bean of [FilingConfiguration]: the start fails on the key alone. Not a
     * `@Configuration`: the component scan of the full context must not pick it up.
     */
    @EnableConfigurationProperties(FilingProperties::class)
    class ZoneOnly {
        @Bean
        fun zone(properties: FilingProperties): ZoneId = FilingConfiguration().filingBusinessZone(properties)
    }

    private val zoneOnly = ApplicationContextRunner().withUserConfiguration(ZoneOnly::class.java)

    @Test
    fun `INV-AC-20947 a start with the key missing fails`() {
        zoneOnly.run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-20947 a start with a blank key fails`() {
        zoneOnly.withPropertyValues("inv.filing.business-zone=").run { (it.startupFailure != null) shouldBe true }
        zoneOnly.withPropertyValues("inv.filing.business-zone=   ").run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-20947 a start with a value that is not a zone id fails`() {
        zoneOnly
            .withPropertyValues(
                "inv.filing.business-zone=Not/AZone",
            ).run { (it.startupFailure != null) shouldBe true }
        zoneOnly
            .withPropertyValues(
                "inv.filing.business-zone=+08:00x",
            ).run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-20947 a start with Asia Singapore succeeds and the zone is that zone`() {
        zoneOnly.withPropertyValues("inv.filing.business-zone=Asia/Singapore").run {
            (it.startupFailure == null) shouldBe true
            it.getBean(ZoneId::class.java) shouldBe ZoneId.of("Asia/Singapore")
        }
    }

    @Test
    fun `INV-AC-20947 the full service context takes the zone from application yaml and fails on a bad value`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.getBean("filingBusinessZone", ZoneId::class.java) shouldBe ZoneId.of("Asia/Singapore")
        }
        SubmissionContext.offline(dir).withPropertyValues("inv.filing.business-zone=Nowhere/Land").run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }

    @Test
    fun `the ConfirmFiling bean is built from the three adapter beans`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context
                .getBeansOfType(FilingDueDates::class.java)
                .values
                .single()
                .javaClass shouldBe
                JooqFilingDueDates::class.java
            context
                .getBeansOfType(LatestChecks::class.java)
                .values
                .single()
                .javaClass shouldBe
                ReadinessLatestChecks::class.java
            context
                .getBeansOfType(GateModesAt::class.java)
                .values
                .single()
                .javaClass shouldBe
                PolicyGateModes::class.java
            context.getBeansOfType(ConfirmFiling::class.java).size shouldBe 1
        }
    }

    /** The `FilingDueDates` double of the real-servlet cases: two dates for P3, the due date of P otherwise. */
    class AmbiguousForP3 {
        @Bean
        @Primary
        fun ambiguousDueDates(): FilingDueDates =
            FilingDueDates { _, period ->
                if (period == "P3") throw AmbiguousDueDateException(LEAK) else LocalDate.of(2026, 4, 30)
            }
    }

    private val loader = javaClass.classLoader
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    private fun token(): String {
        val issued = BreakGlassTestBeans.BEFORE_E.minusSeconds(60)
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("client-1")
                .claim("client_id", "client-1")
                .claim("tenant_id", BreakGlassTestBeans.TENANT_T)
                .claim("scope", "filing-workflow")
                .claim("principal_type", "filing-workflow")
                .claim("principal_scope", "entity:E")
                .jwtID("jti-1")
                .issueTime(Date.from(issued))
                .expirationTime(Date.from(issued.plus(Duration.ofMinutes(10))))
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID("mk")
                .type(JOSEObjectType("at+jwt"))
                .build()
        return SignedJWT(header, claims).also { it.sign(RSASSASigner(keys.private)) }.serialize()
    }

    private fun facts(): Int =
        SubmissionDb.superuser().use { connection ->
            connection
                .prepareStatement("select count(*) from submission.filing_fact where tenant_id = ?::uuid")
                .use { statement ->
                    statement.setString(1, BreakGlassTestBeans.TENANT_T)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    /** The service over a real servlet; a machine caller of `filing-workflow` covering E enters through the filters. */
    private fun serve(
        dir: Path,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        val operations = ContractDocument.operations(ContractDocument.text(loader))
        val services =
            operations
                .flatMap { it.callerFields }
                .filter { it != "any" && !it.startsWith("any-except:") }
                .distinct()
                .sorted()
        val properties =
            services
                .flatMapIndexed { index, service ->
                    listOf(
                        "service-runtime.workload-identity.callers[$index].service=$service",
                        "service-runtime.workload-identity.callers[$index].subject=sa-$service",
                    )
                }.toTypedArray() +
                arrayOf(
                    "service-runtime.event-loops.enabled=false",
                    "service-runtime.machine-identity.issuer=$ISSUER",
                    "service-runtime.machine-identity.audience=$AUDIENCE",
                )
        BreakGlassDoubles.reset(BreakGlassTestBeans.BEFORE_E)
        SubmissionContext
            .startServlet(dir, listOf(BreakGlassTestBeans::class.java, AmbiguousForP3::class.java), properties, loader)
            .use { context ->
                val key =
                    OperatorSessionKey(
                        "mk",
                        "RS256",
                        Base64.getEncoder().encodeToString(keys.public.encoded),
                        BreakGlassTestBeans.E.minus(Duration.ofDays(1)),
                        BreakGlassTestBeans.E.plus(Duration.ofDays(1)),
                        "MACHINE_ACCESS",
                    )
                context.getBean(AuthorityReplica::class.java).keySets.accept(OperatorSessionKeysChanged(listOf(key)))
                block(context)
            }
    }

    private fun confirm(
        context: ConfigurableApplicationContext,
        period: String,
        body: String,
    ): HttpResponse<String> {
        val operation =
            ContractDocument
                .operations(ContractDocument.text(loader))
                .single { it.label == "POST /v1/entities/{entityRef}/periods/{periodRef}/filing" }
        val caller = operation.callerFields.firstOrNull { it != "any" && !it.startsWith("any-except:") } ?: "any"
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/v1/entities/E/periods/$period/filing"))
                .header("X-Workload-Token", "token-$caller")
                .header("Authorization", "Bearer ${token()}")
                .header("Content-Type", "application/json")
        operation.headers.forEach { (name, value) -> request.header(name, value) }
        return HttpClient
            .newHttpClient()
            .send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
    }

    @Test
    fun `INV-AC-20947 real servlet two due dates answer 500 SRT-010 with no detail and record nothing`(
        @TempDir dir: Path,
    ) {
        serve(dir) { context ->
            val before = facts()
            val answer =
                confirm(context, "P3", """{"filedAt":"2026-04-28T20:00:00Z","irasAcknowledgementReference":"A1"}""")
            answer.statusCode() shouldBe 500
            answer.headers().firstValue("Content-Type").orElse("") shouldBe "application/problem+json"
            val json = ObjectMapper().readTree(answer.body())
            json["code"].asString() shouldBe "SRT-010"
            json.has("detail") shouldBe false
            answer.body() shouldNotContain LEAK
            answer.body() shouldNotContain "AmbiguousDueDateException"
            facts() shouldBe before
        }
    }

    @Test
    fun `INV-AC-20947 real servlet no acknowledgement member answers 400 SRT-001 and records nothing`(
        @TempDir dir: Path,
    ) {
        serve(dir) { context ->
            val before = facts()
            val answer = confirm(context, "P", """{"filedAt":"2026-04-28T20:00:00Z"}""")
            answer.statusCode() shouldBe 400
            ObjectMapper().readTree(answer.body())["code"].asString() shouldBe "SRT-001"
            answer.body() shouldNotContain "2026-04-28T20:00:00Z"
            facts() shouldBe before
        }
    }

    private companion object {
        const val LEAK = "two due dates leak-marker"
        const val KEY_BITS = 2048
        const val ISSUER = "https://access.test/"
        const val AUDIENCE = "gst-public-api"
    }
}
