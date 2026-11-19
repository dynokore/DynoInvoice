package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
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
import com.dynokore.gst.reconciliation.application.workpaper.StoredAttestation
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperSource
import com.dynokore.gst.reconciliation.contract.model.AttestWorkPaperRequest
import com.dynokore.gst.reconciliation.contract.model.Attestation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PeriodErrorCode
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.json.JsonParserFactory
import org.springframework.http.ResponseEntity
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-24458 (INV-T-24459): `attestWorkPaper` of [RunsController] over fakes only. */
@Suppress("LargeClass", "TooManyFunctions")
class AttestWorkPaperEndpointTest {
    private val now = Instant.parse("2026-11-19T08:00:00Z")
    private val tenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val r1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val r2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val r9 = UUID.fromString("00000000-0000-0000-0000-000000000009")
    private val unknown = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

    private fun snapshot(
        runId: UUID,
        entity: String,
        residual: String,
    ) = RunSnapshot(
        RecordedRun(runId.toString(), entity, "P1", 2, now, "F5"),
        listOf(
            RunBox(
                "1",
                BigDecimal("100.00"),
                BigDecimal("90.00"),
                BigDecimal("10.00"),
                emptyList(),
                BigDecimal(residual),
            ),
        ),
        BigDecimal(residual),
        emptyList(),
        false,
    )

    private val snapshots =
        listOf(snapshot(r1, "E1", "0.00"), snapshot(r2, "E1", "10.00"), snapshot(r9, "E9", "0.00"))
            .associateBy { it.run.runId }

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
            ): RunSnapshot? = if (tenant == tenantId.toString()) snapshots[runId] else null
        }

    private val inserts = mutableListOf<Triple<String, String, StoredAttestation>>()
    private val attestations = RunAttestations { t, r -> inserts.lastOrNull { it.first == t && it.second == r }?.third }
    private val writer =
        RunAttestationWriter { t, r, s ->
            inserts += Triple(t, r, s)
            true
        }
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

    private val useCase =
        AttestWorkPaper(
            reader,
            GetWorkPaper(reader, noAnnotations, attestations),
            RunPreparers { _, _ -> "U1" },
            SingleAttestationPolicy { _, _, _ -> null },
            attestations,
            writer,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    /** The events of the call in order: "decide", "attest" and "seal" lines. */
    private val events = mutableListOf<String>()
    private val asked = mutableListOf<List<String>>()
    private val sealed = mutableListOf<Pair<String, String>>()
    private var sealThrows = false
    private var allow = true
    private var authorityThrows = false

    private val seal =
        SealWorkPaper(
            WorkPaperSource { t, r ->
                events += "seal"
                sealed += t to r
                if (sealThrows) error("seal store unavailable")
                null
            },
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
                ): String = error("not reached")
            },
            ArtefactRecordedPublisher { _, _, _, _, _ -> error("not reached") },
        )

    private val authority =
        AttestAuthority { user, permission, entity ->
            events += "decide"
            asked += listOf(tenantId.toString(), user, entity, permission)
            if (authorityThrows) error("replica unavailable")
            allow
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
            useCase,
            seal,
            authority,
            beans.getBeanProvider(HttpServletRequest::class.java),
        )
    }

    private val parser = JsonParserFactory.getJsonParser()

    private fun call(
        user: String,
        run: UUID,
        flag: Boolean = false,
        principal: EndUserPrincipal = principal(user),
    ): ResponseEntity<*> =
        try {
            CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(2), Clock.fixed(now, ZoneOffset.UTC))) {
                controller(principal).attestWorkPaper(run, AttestWorkPaperRequest(flag))
            }
        } catch (refusal: AttestationRefusal) {
            AttestationRefusalAdvice().refuse(refusal)
        }

    private fun json(response: ResponseEntity<*>): Map<String, Any?> = parser.parseMap(response.body as String)

    private fun assertEnvelope(
        response: ResponseEntity<*>,
        status: Int,
        code: String,
        title: String,
        severity: String,
        disposition: String,
        owner: String,
    ) {
        response.statusCode.value() shouldBe status
        response.headers.getFirst("Content-Type") shouldBe Problems.CONTENT_TYPE
        val body = json(response)
        body["status"] as Int shouldBe status
        body["code"] shouldBe code
        body["type"] shouldBe Problems.typeOf(code)
        body["title"] shouldBe title
        body["severity"] shouldBe severity
        body["disposition"] shouldBe disposition
        body["remediationOwner"] shouldBe owner
    }

    private fun assertPeriod(
        response: ResponseEntity<*>,
        entry: PeriodErrorCode,
    ) = assertEnvelope(
        response,
        entry.httpStatus,
        entry.code,
        entry.title,
        entry.severity,
        entry.disposition,
        entry.owner.text,
    )

    private fun assertClosed(
        response: ResponseEntity<*>,
        entry: ClosedErrorCode,
    ) = assertEnvelope(
        response,
        entry.httpStatus,
        entry.code,
        entry.title,
        "BLOCK",
        entry.disposition,
        entry.remediationOwner,
    )

    @Test
    fun `INV-AC-24458 the first call answers 200 with the user ID, asks once, stores once and seals once`() {
        val response = call("U2", r1)
        response.statusCode.value() shouldBe 200
        val body = response.body as Attestation
        body.attestedBy shouldBe "U2"
        body.attestedAt shouldBe now.atOffset(ZoneOffset.UTC)
        body.soleAttester shouldBe null
        asked shouldBe listOf(listOf(tenantId.toString(), "U2", "E1", "reconciliation.attest"))
        inserts.map { it.third.attestedBy } shouldBe listOf("U2")
        sealed shouldBe listOf(tenantId.toString() to r1.toString())
        events shouldBe listOf("decide", "seal")
    }

    @Test
    fun `INV-AC-24458 a repeat answers 200 with the same attestation and no second row`() {
        val first = call("U2", r1).body
        val repeat = call("U2", r1)
        repeat.statusCode.value() shouldBe 200
        repeat.body shouldBe first
        inserts.size shouldBe 1
    }

    @Test
    fun `INV-AC-24458 a denied decision answers 403 SRT-004 and the use case and the seal are not called`() {
        allow = false
        val response = call("U2", r1)
        assertClosed(response, ClosedErrorCode.ACCESS_DENIED)
        ClosedErrorCode.ACCESS_DENIED.code shouldBe "SRT-004"
        inserts shouldBe emptyList()
        events shouldBe listOf("decide")
    }

    @Test
    fun `INV-AC-24458 the preparer without the acknowledgement answers 403 PER-017 and no seal`() {
        val response = call("U1", r1, flag = false)
        assertPeriod(response, PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES)
        PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES.code shouldBe "PER-017"
        PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES.httpStatus shouldBe 403
        inserts shouldBe emptyList()
        sealed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24458 a run with a residual answers 409 PER-018 and no seal`() {
        val response = call("U2", r2)
        assertPeriod(response, PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL)
        PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL.code shouldBe "PER-018"
        PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL.httpStatus shouldBe 409
        inserts shouldBe emptyList()
        sealed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24458 an unknown run answers 404 SRT-005 with no decision`() {
        val response = call("U2", unknown)
        assertClosed(response, ClosedErrorCode.NOT_FOUND)
        ClosedErrorCode.NOT_FOUND.code shouldBe "SRT-005"
        events shouldBe emptyList()
        sealed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24458 a run of an entity outside the current entity set answers 404 SRT-005 with no decision`() {
        val response = call("U2", r9)
        assertClosed(response, ClosedErrorCode.NOT_FOUND)
        events shouldBe emptyList()
        inserts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24458 an authority exception propagates and nothing is attested or sealed`() {
        authorityThrows = true
        shouldThrow<IllegalStateException> { call("U2", r1) }.message shouldBe "replica unavailable"
        inserts shouldBe emptyList()
        sealed shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24458 a seal exception propagates after the store and a repeat replays and seals again`() {
        sealThrows = true
        shouldThrow<IllegalStateException> { call("U2", r1) }.message shouldBe "seal store unavailable"
        inserts.size shouldBe 1
        sealThrows = false
        val repeat = call("U2", r1)
        repeat.statusCode.value() shouldBe 200
        inserts.size shouldBe 1
        sealed.size shouldBe 2
    }

    @Test
    fun `INV-AC-24458 no token answers 401 SRT-003 with no decision`() {
        val response =
            try {
                controller(null).attestWorkPaper(r1, AttestWorkPaperRequest(false))
            } catch (refusal: AttestationRefusal) {
                AttestationRefusalAdvice().refuse(refusal)
            }
        assertClosed(response, ClosedErrorCode.UNAUTHENTICATED)
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24458 the authority grants only a LOCAL or FALLBACK allow and asks the scoped key for the tenant`() {
        val seen = mutableListOf<AuthorizationRequest>()

        fun authority(
            allowed: Boolean,
            source: DecisionSource,
        ) = AuthorityReplicaAttestAuthority(
            { request, _ ->
                seen += request
                AuthorityDecision(allowed, source, DecisionReason.GRANTED)
            },
            tenants,
        )

        val deadline = Deadline.after(Duration.ofSeconds(2), Clock.fixed(now, ZoneOffset.UTC))

        fun holds(a: AttestAuthority) = CurrentDeadline.callWith(deadline) { a.holds("U2", ATTEST_PERMISSION, "E1") }
        holds(authority(true, DecisionSource.LOCAL)) shouldBe true
        holds(authority(true, DecisionSource.FALLBACK)) shouldBe true
        holds(authority(true, DecisionSource.DEGRADED)) shouldBe false
        holds(authority(false, DecisionSource.LOCAL)) shouldBe false
        seen.first().tenant shouldBe tenantId
        seen.first().principal.id shouldBe "U2"
        seen.first().principal.kind shouldBe PrincipalKind.HUMAN
        seen.first().entityRef shouldBe "E1"
        seen.first().action shouldBe "reconciliation.attest"
        ATTEST_PERMISSION shouldBe "reconciliation.attest"
    }

    private fun root(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("gradle/libs.versions.toml"))) dir = dir.parent
        return requireNotNull(dir) { "gradle/libs.versions.toml not found above the working directory" }
    }

    private fun atLeast(
        actual: String,
        minimum: String,
    ): Boolean {
        val a = actual.split('.').map { it.toInt() }
        val m = minimum.split('.').map { it.toInt() }
        for (i in 0 until maxOf(a.size, m.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = m.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    @Test
    fun `INV-AC-24458 the BOM pin and the resolved service-runtime carry the PER-017 and PER-018 builders`() {
        val repo = root()
        val pin =
            Files
                .readAllLines(repo.resolve("gradle/libs.versions.toml"))
                .single { it.startsWith("platformLibsBom") }
                .substringAfter('"')
                .substringBefore('"')
        atLeast(pin, "0.226.0") shouldBe true
        val resolved =
            Files
                .readAllLines(repo.resolve("reconciliation-adapters/gradle.lockfile"))
                .single { it.startsWith("com.dynokore.platform:service-runtime:") }
                .substringAfter("service-runtime:")
                .substringBefore('=')
        atLeast(resolved, "13.25.0") shouldBe true
    }
}
