package com.dynokore.gst.controlPlane.adapters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.controlPlane.adapters.access.HttpFirstAdministratorAccess
import com.dynokore.gst.controlPlane.adapters.persistence.ProvisioningRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.RegionListJooq
import com.dynokore.gst.controlPlane.adapters.persistence.ReissueAttemptRepositoryJooq
import com.dynokore.gst.controlPlane.application.commercial.CallerCredential
import com.dynokore.gst.controlPlane.application.commercial.InvitationDelivery
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenant
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenantCommand
import com.dynokore.gst.controlPlane.application.commercial.ReissueFirstAdministratorInvitation
import com.dynokore.gst.controlPlane.application.commercial.ReissueInvitationCommand
import com.dynokore.gst.controlPlane.application.commercial.TenantCreation
import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The invitation code is in no log line and no stored row (INV-AC-8224, INV-AC-8281, INV-Q-17793 A; OWASP ASVS V7.1.1
 * and V8.3.4: no secret in a log). The provision flow and the reissue flow run end to end over the real repositories
 * and a real [HttpFirstAdministratorAccess] against a stub of access-svc that answers with a distinctive code, also in
 * the bodies of its failure answers. Every log event of every logger is captured and the rows of both tables are read
 * back. The two control tests show that the search fails when a log line or a row carries the code.
 */
class FirstAdministratorCodeLeakIT {
    private companion object {
        const val CODE = "CODE-b7e1c3a9-distinctive-0f42"
        const val DEFAULT_BODY =
            "{\"grantRef\":\"GRT-1\",\"status\":\"PENDING\",\"invitationRef\":\"INVN-9\"," +
                "\"invitationCode\":\"$CODE\"}"
    }

    private val dsl: DSLContext = PostgresDb.dsl()
    private val now = Instant.parse("2026-11-10T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var status = 201
    private var answerBody: String? = null
    private var delayMillis = 0L
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.readAllBytes()
                Thread.sleep(delayMillis)
                val body =
                    (answerBody ?: DEFAULT_BODY).toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val rootLevel = root.level

    @BeforeEach
    fun capture() {
        root.level = Level.TRACE
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun release() {
        root.detachAppender(appender)
        appender.stop()
        root.level = rootLevel
        server.stop(0)
    }

    private fun access(timeout: Duration = Duration.ofSeconds(2)) =
        HttpFirstAdministratorAccess(
            URI.create("http://127.0.0.1:${server.address.port}"),
            WorkloadTokenSource { WorkloadToken("workload-token-1") },
            timeout,
        )

    private fun delivery(timeout: Duration = Duration.ofSeconds(2)) =
        InvitationDelivery(
            // the signed invitation is the one place that carries the code (INV-AC-8224); it is not logged or stored
            { contract, tenant, issued, _, _ ->
                "signed:$contract:$tenant:${issued.invitationRef}:${issued.invitationCode.value}"
            },
            { _, _, _, _ -> },
            access(timeout),
            clock,
            Duration.ofDays(7),
        )

    private val provisionings = ProvisioningRepositoryJooq(dsl, clock)
    private var counter = 0

    private fun provisionOperation(timeout: Duration = Duration.ofSeconds(2)) =
        ProvisionTenant(
            provisionings,
            RegionListJooq(dsl),
            { TenantCreation.Created },
            delivery(timeout),
            OperatorRoles { actor, role -> actor == "carol" && role == OperatorRole.COMMERCIAL_ADMIN },
        ) { CommercialRef("TEN-leak-${++counter}-${UUID.randomUUID().toString().take(6)}") }

    private fun provision(
        contract: String,
        timeout: Duration = Duration.ofSeconds(2),
    ) {
        val operation = provisionOperation(timeout)
        val key = UUID.randomUUID()
        val command =
            ProvisionTenantCommand(
                ActorId("carol"),
                CommercialRef(contract),
                "Acme",
                PlanRef("P"),
                "admin@acme.example",
                RegionKey("SG"),
                key,
                "fp:$contract",
                CallerCredential("Bearer t"),
                RequestContext("corr", key.toString()),
            )
        val state = operation.load(command)
        if (operation.unmetPrecondition(command, state) == null) operation.perform(command, state)
    }

    private fun reissue(
        contract: String,
        timeout: Duration = Duration.ofSeconds(2),
    ) {
        val operation =
            ReissueFirstAdministratorInvitation(
                provisionings,
                ReissueAttemptRepositoryJooq(dsl, clock),
                delivery(timeout),
                OperatorPermissions { _, key -> key == ReissueFirstAdministratorInvitation.PERMISSION_KEY },
            )
        val key = UUID.randomUUID()
        val command =
            ReissueInvitationCommand(ActorId("carol"), CommercialRef(contract), key, RequestContext("corr", "c"))
        val state = operation.load(command)
        if (operation.unmetPrecondition(command, state) == null) operation.perform(command, state)
    }

    /** Every captured event in full: message, arguments, markers, MDC and the throwable with its causes. */
    private fun logLinesHolding(code: String): List<String> =
        appender.list
            .map { event ->
                listOf(
                    event.formattedMessage,
                    event.message,
                    event.argumentArray?.joinToString("|") { it.toString() }.orEmpty(),
                    event.mdcPropertyMap.toString(),
                    event.markerList?.toString().orEmpty(),
                    event.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty(),
                ).joinToString("\n")
            }.filter { it.contains(code) }

    private fun rowsHolding(
        code: String,
        vararg selects: String,
    ): List<String> = selects.flatMap { sql -> dsl.fetch(sql).map { it.formatJSON() } }.filter { it.contains(code) }

    private val storedRows =
        arrayOf(
            "select t::text from controlplane.tenant_provisioning_request t",
            "select r::text from controlplane.first_invitation_reissue r",
        )

    @Test
    fun `INV-AC-8224 the provision flow leaves the invitation code in no log line and no stored row`() {
        provision("CTR-leak-ok")
        status = 500
        provision("CTR-leak-500")
        status = 503
        provision("CTR-leak-503")
        status = 409
        provision("CTR-leak-409")
        status = 200
        answerBody = "not json $CODE"
        provision("CTR-leak-notjson")
        answerBody = """{"invitationCode":"$CODE"}"""
        provision("CTR-leak-noref")
        status = 201
        answerBody = null
        delayMillis = 800
        provision("CTR-leak-slow", Duration.ofMillis(100))
        dsl.fetchCount(
            org.jooq.impl.DSL
                .table("controlplane.tenant_provisioning_request"),
            org.jooq.impl.DSL
                .field("contract_reference")
                .like("CTR-leak-%"),
        ) shouldBe 7
        appender.list.shouldNotBeEmpty()
        logLinesHolding(CODE).shouldBeEmpty()
        rowsHolding(CODE, *storedRows).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8281 the reissue flow leaves the invitation code in no log line and no stored row`() {
        provision("CTR-reissue-leak")
        listOf(200 to null, 500 to null, 503 to null, 409 to null, 200 to "not json $CODE", 200 to "{\"x\":\"$CODE\"}")
            .forEach { (code, body) ->
                status = code
                answerBody = body
                reissue("CTR-reissue-leak")
            }
        status = 200
        answerBody = null
        delayMillis = 800
        reissue("CTR-reissue-leak", Duration.ofMillis(100))
        dsl.fetchCount(
            org.jooq.impl.DSL
                .table("controlplane.first_invitation_reissue"),
            org.jooq.impl.DSL
                .field("contract_reference")
                .eq("CTR-reissue-leak"),
        ) shouldBe 7
        appender.list.shouldNotBeEmpty()
        logLinesHolding(CODE).shouldBeEmpty()
        rowsHolding(CODE, *storedRows).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8224 the search finds the code when a log line or a row carries it`() {
        LoggerFactory.getLogger(HttpFirstAdministratorAccess::class.java).warn("answer {}", CODE)
        LoggerFactory.getLogger("other").warn("failed", IllegalStateException("cause $CODE"))
        logLinesHolding(CODE).size shouldBe 2
        rowsHolding(CODE, "select '$CODE' as v").size shouldBe 1
        rowsHolding("never-present-xyz", "select '$CODE' as v").shouldBeEmpty()
    }
}
