package com.dynokore.gst.reconciliation.adapters.http

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.reconciliation.adapters.export.UnsupportedExportTextException
import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunItems
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.workpaper.ArtefactRecordedPublisher
import com.dynokore.gst.reconciliation.application.workpaper.AttestWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestationWriter
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.RunPreparers
import com.dynokore.gst.reconciliation.application.workpaper.SealWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SealedRef
import com.dynokore.gst.reconciliation.application.workpaper.SealedWorkPapers
import com.dynokore.gst.reconciliation.application.workpaper.SingleAttestationPolicy
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperSource
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportContentTypes
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsSource
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsUnavailable
import com.dynokore.gst.reconciliation.application.workpaper.export.RenderedExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.json.JsonParserFactory
import org.springframework.core.io.Resource
import org.springframework.http.ResponseEntity
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val SECRET = "secret-field-value-9f3"

/** INV-AC-24485 (INV-T-24486) and INV-AC-24541 (INV-T-24543): `exportWorkPaper` of [RunsController] over fakes only. */
@Suppress("TooManyFunctions", "LargeClass")
class ExportWorkPaperEndpointTest {
    private val now = Instant.parse("2026-11-19T08:00:00Z")
    private val tenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val r1 = UUID.fromString("00000000-0000-0000-0000-00000000ABCD")
    private val unknown = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
    private val bytesB = byteArrayOf(1, 2, 3, 4)

    private val snapshot =
        RunSnapshot(
            RecordedRun(r1.toString(), "E1", "P1", 2, now, "F5"),
            listOf(
                RunBox(
                    "1",
                    BigDecimal("100.00"),
                    BigDecimal("90.00"),
                    BigDecimal("10.00"),
                    emptyList(),
                    BigDecimal("0.00"),
                ),
            ),
            BigDecimal("0.00"),
            emptyList(),
            false,
        )

    /** The events of one call in order. */
    private val events = mutableListOf<String>()
    private val asked = mutableListOf<List<String>>()
    private val rendered = mutableListOf<ExportFormat>()
    private val audited = mutableListOf<AuditEntry>()
    private val contexts = mutableListOf<EventContext>()
    private var allow = true
    private var relaxedCurrent = true
    private var vanishAfterFirstRead = false
    private var auditThrows = false
    private var renderThrows = false
    private var reads = 0

    private val reader =
        object : RunReader {
            override fun latest(
                tenant: String,
                entityRef: String,
                periodRef: String,
            ): RunSnapshot? = null

            override fun page(
                tenant: String,
                entityRef: String,
                periodRef: String,
                limit: Int,
                after: RunCursor?,
            ): List<RecordedRun> = emptyList()

            override fun find(
                tenant: String,
                runId: String,
            ): RunSnapshot? {
                reads++
                val gone = vanishAfterFirstRead && reads > 1
                return if (tenant == tenantId.toString() && runId == r1.toString() && !gone) snapshot else null
            }
        }

    private val attestations = RunAttestations { _, _ -> null }
    private val noAnnotations =
        object : RunAnnotations {
            override fun latest(
                tenant: String,
                runId: String,
                box: String,
            ): StoredAnnotation? = null

            override fun all(
                tenant: String,
                runId: String,
            ): List<StoredAnnotation> = emptyList()

            override fun insert(
                tenant: String,
                stored: StoredAnnotation,
            ): Boolean = false
        }

    private val renderer =
        WorkPaperRenderer { _, format ->
            events += "render"
            rendered += format
            if (renderThrows) throw UnsupportedExportTextException("Items row 3 evidence")
            RenderedExport(
                if (format == ExportFormat.PDF) ExportContentTypes.PDF else ExportContentTypes.SPREADSHEET,
                bytesB,
            )
        }

    private val relaxed =
        RelaxedControlsSource { _, _, _ ->
            events += "use-case"
            if (!relaxedCurrent) throw RelaxedControlsUnavailable("replica not current")
            emptyList()
        }

    private val exportUseCase =
        ExportWorkPaper(
            GetWorkPaper(reader, noAnnotations, attestations),
            noAnnotations,
            RunItems { _, _ -> emptyList() },
            relaxed,
            renderer,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private val authority =
        ExportAuthority { user, permission, entity ->
            events += "decide"
            asked += listOf(tenantId.toString(), user, entity, permission)
            allow
        }

    private val auditedOperations =
        object : AuditedOperations {
            override fun <T> change(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = error("a read never changes state")

            override fun <T> read(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T {
                events += "audit-write"
                if (auditThrows) error("audit store unavailable")
                audited += entry
                contexts += context
                return block().also { events += "returned" }
            }
        }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenantId

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun principal(
        user: String,
        current: Boolean = true,
    ) = EndUserPrincipal(user, tenantId, setOf(EntityRef("E1")), current)

    private fun request(principal: EndUserPrincipal?): HttpServletRequest =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
            if (method.name == "getAttribute" && args?.get(0) == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) {
                principal
            } else {
                null
            }
        } as HttpServletRequest

    private fun controller(principal: EndUserPrincipal?): RunsController {
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("request", request(principal))
        return RunsController(
            ListRuns(reader),
            GetRun(reader),
            GetWorkPaper(reader, noAnnotations, attestations),
            runner,
            tenants,
            AttestWorkPaper(
                reader,
                GetWorkPaper(reader, noAnnotations, attestations),
                RunPreparers { _, _ -> "U1" },
                SingleAttestationPolicy { _, _, _ -> null },
                attestations,
                RunAttestationWriter { _, _, _ -> error("this fixture never attests") },
                Clock.fixed(now, ZoneOffset.UTC),
            ),
            SealWorkPaper(
                WorkPaperSource { _, _ -> null },
                object : SealedWorkPapers {
                    override fun latest(
                        tenant: String,
                        runId: String,
                    ): SealedRef? = null

                    override fun put(
                        tenant: String,
                        entityRef: String,
                        runId: String,
                        sealVersion: Int,
                        bytes: ByteArray,
                    ): String = error("this fixture never seals")
                },
                ArtefactRecordedPublisher { _, _, _, _, _ -> error("this fixture never seals") },
            ),
            { _, _, _ -> error("this fixture never attests") },
            beans.getBeanProvider(HttpServletRequest::class.java),
            exportUseCase,
            authority,
            auditedOperations,
        )
    }

    private fun call(
        run: UUID,
        format: String,
        principal: EndUserPrincipal? = principal("U7"),
    ): ResponseEntity<Resource> =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(2), Clock.fixed(now, ZoneOffset.UTC))) {
            controller(principal).exportWorkPaper(run, format)
        }

    private fun assertClosed(
        response: ResponseEntity<*>,
        e: ClosedErrorCode,
    ) {
        response.statusCode.value() shouldBe e.httpStatus
        response.headers.getFirst("Content-Type") shouldBe Problems.CONTENT_TYPE
        val body = JsonParserFactory.getJsonParser().parseMap(response.body as String)
        body["status"] as Int shouldBe e.httpStatus
        body["code"] shouldBe e.code
        body["type"] shouldBe Problems.typeOf(e.code)
        body["title"] shouldBe e.title
    }

    private val permission = listOf(tenantId.toString(), "U7", "E1", "reconciliation.export")

    @Test
    fun `INV-AC-24485 (a) the spreadsheet is delivered after the decision and the audit entry`() {
        val response = call(r1, "SPREADSHEET")
        response.statusCode.value() shouldBe 200
        response.headers.getFirst("Content-Type") shouldBe ExportContentTypes.SPREADSHEET
        (response.body as Resource).inputStream.readBytes().toList() shouldBe bytesB.toList()
        asked shouldBe listOf(permission)
        audited shouldBe
            listOf(
                AuditEntry(
                    "U7",
                    "exportWorkPaper",
                    "run:00000000-0000-0000-0000-00000000abcd/format:SPREADSHEET",
                    null,
                    null,
                ),
            )
        contexts.map { it.tenant to it.entity } shouldBe listOf(tenantId to EntityRef("E1"))
        rendered shouldBe listOf(ExportFormat.SPREADSHEET)
        events shouldBe listOf("decide", "use-case", "render", "audit-write", "returned")
    }

    @Test
    fun `INV-AC-24541 (b) the PDF format is allowed, rendered as PDF and delivered with one audit entry`() {
        val response = call(r1, "PDF")
        response.statusCode.value() shouldBe 200
        response.headers.getFirst("Content-Type") shouldBe ExportContentTypes.PDF
        (response.body as Resource).inputStream.readBytes().toList() shouldBe bytesB.toList()
        asked shouldBe listOf(permission)
        rendered shouldBe listOf(ExportFormat.PDF)
        audited shouldBe
            listOf(
                AuditEntry("U7", "exportWorkPaper", "run:00000000-0000-0000-0000-00000000abcd/format:PDF", null, null),
            )
        events shouldBe listOf("decide", "use-case", "render", "audit-write", "returned")
    }

    @Test
    fun `INV-AC-24485 (c) a denied decision answers 403 SRT-004 with no use case and no audit entry`() {
        allow = false
        val response = call(r1, "SPREADSHEET")
        assertClosed(response, ClosedErrorCode.ACCESS_DENIED)
        ClosedErrorCode.ACCESS_DENIED.code shouldBe "SRT-004"
        events shouldBe listOf("decide")
        audited shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24485 (d) an unknown run answers 404 SRT-005 and no decision is asked`() {
        val response = call(unknown, "SPREADSHEET")
        assertClosed(response, ClosedErrorCode.NOT_FOUND)
        ClosedErrorCode.NOT_FOUND.code shouldBe "SRT-005"
        asked shouldBe emptyList()
        events shouldBe emptyList()
        audited shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24485 (d) a run of an entity outside the current entity set answers 404 and no decision`() {
        val outside = EndUserPrincipal("U7", tenantId, setOf(EntityRef("E9")), true)
        assertClosed(call(r1, "SPREADSHEET", outside), ClosedErrorCode.NOT_FOUND)
        asked shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24485 (e) a policy replica that is not current answers 503 SRT-013 and no audit entry`() {
        relaxedCurrent = false
        val response = call(r1, "SPREADSHEET")
        assertClosed(response, ClosedErrorCode.SERVICE_NOT_READY)
        ClosedErrorCode.SERVICE_NOT_READY.code shouldBe "SRT-013"
        audited shouldBe emptyList()
        rendered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24485 (f) a run that vanishes after it was read answers 404 SRT-005`() {
        vanishAfterFirstRead = true
        val response = call(r1, "SPREADSHEET")
        assertClosed(response, ClosedErrorCode.NOT_FOUND)
        audited shouldBe emptyList()
        events shouldBe listOf("decide")
    }

    @Test
    fun `INV-AC-24485 (g) no token answers 401 and nothing is read or decided`() {
        val response = call(r1, "SPREADSHEET", principal = null)
        assertClosed(response, ClosedErrorCode.UNAUTHENTICATED)
        response.statusCode.value() shouldBe 401
        reads shouldBe 0
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24485 (h) a failed audit write propagates and no body is returned`() {
        auditThrows = true
        shouldThrow<IllegalStateException> { call(r1, "SPREADSHEET") }
        audited shouldBe emptyList()
        events.contains("returned") shouldBe false
    }

    @Test
    fun `INV-AC-24541 (i) a denied decision with the PDF format answers 403 SRT-004 and nothing is rendered`() {
        allow = false
        val response = call(r1, "PDF")
        assertClosed(response, ClosedErrorCode.ACCESS_DENIED)
        ClosedErrorCode.ACCESS_DENIED.code shouldBe "SRT-004"
        events shouldBe listOf("decide")
        audited shouldBe emptyList()
        rendered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24541 (j) text the font cannot draw answers 500 SRT-010 with no detail, audit or log value`() {
        renderThrows = true
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val response =
            try {
                call(r1, "PDF")
            } finally {
                logger.detachAppender(appender)
            }
        assertClosed(response, ClosedErrorCode.INTERNAL_ERROR)
        ClosedErrorCode.INTERNAL_ERROR.code shouldBe "SRT-010"
        val text = (response as ResponseEntity<*>).body as String
        JsonParserFactory.getJsonParser().parseMap(text).containsKey("detail") shouldBe false
        text.contains(SECRET) shouldBe false
        audited shouldBe emptyList()
        events shouldBe listOf("decide", "use-case", "render")
        appender.list.any {
            it.formattedMessage.contains(SECRET) || it.throwableProxy?.message?.contains(SECRET) == true
        } shouldBe false
    }

    @Test
    fun `INV-AC-24485 (j) an unknown format answers 400 SRT-001 after the decision and renders nothing`() {
        val response = call(r1, "CSV")
        val e = ClosedErrorCode.CONTRACT_VIOLATION
        e.code shouldBe "SRT-001"
        response.statusCode.value() shouldBe 400
        response.headers.getFirst("Content-Type") shouldBe Problems.CONTENT_TYPE
        val body = (response as ResponseEntity<*>).body as ErrorEnvelope
        body.status shouldBe 400
        body.code shouldBe "SRT-001"
        body.title shouldBe e.title
        events shouldBe listOf("decide")
        audited shouldBe emptyList()
        rendered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24485 the authority asks the replica for the key and only LOCAL or FALLBACK allowed grants`() {
        val seen = mutableListOf<AuthorizationRequest>()

        fun holds(decision: AuthorityDecision): Boolean {
            val authority =
                AuthorityReplicaExportAuthority(
                    { request, _ ->
                        seen += request
                        decision
                    },
                    tenants,
                )
            return CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(2), Clock.fixed(now, ZoneOffset.UTC))) {
                authority.holds("U7", EXPORT_PERMISSION, "E1")
            }
        }

        fun decision(
            allowed: Boolean,
            source: DecisionSource,
        ) = AuthorityDecision(allowed, source, if (allowed) DecisionReason.GRANTED else DecisionReason.NO_GRANT)
        holds(decision(true, DecisionSource.LOCAL)) shouldBe true
        holds(decision(true, DecisionSource.FALLBACK)) shouldBe true
        holds(decision(true, DecisionSource.DEGRADED)) shouldBe false
        holds(decision(false, DecisionSource.LOCAL)) shouldBe false
        EXPORT_PERMISSION shouldBe "reconciliation.export"
        seen.first().principal.kind shouldBe PrincipalKind.HUMAN
        seen.first().action shouldBe "reconciliation.export"
    }
}
