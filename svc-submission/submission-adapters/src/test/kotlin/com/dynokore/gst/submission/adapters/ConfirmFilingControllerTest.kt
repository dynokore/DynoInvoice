package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.FilingPeriodRefusal
import com.dynokore.gst.submission.adapters.http.FilingRefusalAdvice
import com.dynokore.gst.submission.adapters.http.ReadinessController
import com.dynokore.gst.submission.adapters.http.ReadinessRefusalAdvice
import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.filing.AmbiguousDueDateException
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.ConfirmFilingActions
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.gst.submission.application.filing.FilingFact
import com.dynokore.gst.submission.application.filing.FilingFactStore
import com.dynokore.gst.submission.application.filing.GateModesAt
import com.dynokore.gst.submission.application.filing.NewFilingFact
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.contract.model.ConfirmFilingRequest
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** `confirmFiling` over the controller (INV-AC-20947): the real use case over in-memory ports. */
class ConfirmFilingControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-04-10T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val zone = ZoneId.of("Asia/Singapore")
    private val mapper = jacksonObjectMapper()

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** The stored facts; an identical fact is appended once; `recordedAt` is the store's stamp. */
    private class Store : FilingFactStore {
        val stored = mutableListOf<FilingFact>()
        val recordedBy get() = stored.map { it.recordedBy }

        override fun facts(
            entityRef: String,
            periodRef: String,
        ) = stored.filter { it.entityRef == entityRef && it.periodRef == periodRef }

        override fun append(fact: NewFilingFact) {
            val same =
                stored.any {
                    it.entityRef == fact.entityRef && it.periodRef == fact.periodRef && it.filedAt == fact.filedAt &&
                        it.acknowledgementRef == fact.acknowledgementRef && it.correction == fact.correction
                }
            if (!same) {
                stored +=
                    FilingFact(
                        fact.entityRef,
                        fact.periodRef,
                        fact.filedAt,
                        fact.acknowledgementRef,
                        fact.portalRef,
                        fact.correction,
                        fact.recordedBy,
                        Instant.parse("2026-04-18T08:00:00Z").plusSeconds(stored.size.toLong()),
                    )
            }
        }
    }

    private val store = Store()
    private val notReady = LatestCheck("chk-8", CheckVerdict.NOT_READY)
    private val ready = LatestCheck("chk-7", CheckVerdict.READY)
    private var transactionEntries = 0
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                transactionEntries++
                return block()
            }
        }
    private val dueDates =
        FilingDueDates { _, period ->
            when (period) {
                "P2" -> null
                "P3" -> throw AmbiguousDueDateException("two dates")
                else -> LocalDate.of(2026, 4, 30)
            }
        }
    private val latest =
        object : LatestChecks {
            override fun latest(
                entityRef: String,
                periodRef: String,
            ) = if (periodRef == "P4") notReady else ready

            override fun knownCheckIds(
                entityRef: String,
                periodRef: String,
            ): Set<String> = emptySet()
        }

    private fun replica(): AuthorityReplica {
        val r = AuthorityReplica(clock, roleMap)
        r.onSnapshotComplete(0)
        val role =
            roleMap
                .rolesFor(ConfirmFilingActions.PERSON)
                .keys
                .sorted()
                .first()
        val grant = RoleGrant(role, GrantScope.ENTITY, "E", clock.instant().minusSeconds(60))
        r.accept(
            AuthorityChanged(
                tenant,
                "u1",
                1,
                AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, grant),
            ),
        )
        return r
    }

    private val confirm =
        ConfirmFiling(
            replica(),
            dueDates,
            GateModesAt { _, _ -> GateMode.BLOCKING },
            latest,
            ReleasedOverrideChecks { _, _ -> emptySet() },
            store,
        )

    private fun person(id: String) = EndUserPrincipal(id, tenant, setOf(EntityRef("E")), true)

    private fun machine(coverage: String) =
        MachinePrincipalFixtures.machine(
            "m-$coverage",
            tenant,
            MachineEntityCoverage.Entity(coverage),
            "filing-workflow",
        )

    private fun controller(
        user: EndUserPrincipal? = null,
        machine: Principal? = null,
    ): ReadinessController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                when (args[0]) {
                    EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE -> user
                    MachineTokenFilter.PRINCIPAL_ATTRIBUTE -> machine
                    else -> null
                }
            } as HttpServletRequest
        val fixture = DrainFixture()
        return ReadinessController(
            GetLatestReadinessCheck(
                object : ReadinessCheckReader {
                    override fun latest(
                        entityRef: String,
                        periodRef: String,
                    ): StoredReadinessCheck? = null

                    override fun page(
                        entityRef: String,
                        periodRef: String,
                        limit: Int,
                        before: Long?,
                    ): List<StoredReadinessCheck> = emptyList()
                },
                OverrideAuthority { _, _, _ -> false },
            ),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
            fixture.preview(),
            fixture.drain(),
            confirm,
            runner,
            zone,
        )
    }

    private fun body(
        filedAt: String,
        ack: String = "A1",
        correction: Boolean = false,
    ) = ConfirmFilingRequest(java.time.OffsetDateTime.parse(filedAt), ack, null, correction)

    /** Status and body; a refusal is rendered by the advice of the controller, as the framework does. */
    private fun post(
        c: ReadinessController,
        period: String,
        request: ConfirmFilingRequest,
    ): Pair<Int, String> {
        val thrown = runCatching { CurrentDeadline.callWith(deadline) { c.confirmFiling("E", period, request) } }
        val failure = thrown.exceptionOrNull()
        if (failure != null) {
            val response: ResponseEntity<String> =
                when (failure) {
                    is ComplianceRefusal -> ReadinessRefusalAdvice().refuse(failure)
                    is FilingPeriodRefusal -> FilingRefusalAdvice().refuse(failure)
                    is AmbiguousDueDateException -> FilingRefusalAdvice().ambiguous(failure)
                    else -> throw failure
                }
            return response.statusCode.value() to response.body!!
        }
        val response = thrown.getOrThrow()
        return response.statusCode.value() to mapper.writeValueAsString(response.body)
    }

    @Test
    fun `INV-AC-20947 the first call answers the business-zone date and the stored recorded instant`() {
        val (status, text) = post(controller(person("u1")), "P", body("2026-04-28T20:00:00Z"))
        status shouldBe 200
        val json = mapper.readTree(text)
        json["governingFiledAt"].asString() shouldBe "2026-04-29T00:00:00+08:00"
        json["filedAt"].asString() shouldBe "2026-04-29T00:00:00+08:00"
        java.time.OffsetDateTime
            .parse(json["confirmedAt"].asString())
            .toInstant() shouldBe store.stored.single().recordedAt
        store.stored.single().filedAt shouldBe LocalDate.of(2026, 4, 29)
    }

    @Test
    fun `INV-AC-20947 the same moment with another offset is a repeat with an unchanged body`() {
        val c = controller(person("u1"))
        val first = post(c, "P", body("2026-04-28T20:00:00Z"))
        val repeat = post(c, "P", body("2026-04-29T04:00:00+08:00"))
        repeat shouldBe first
        store.stored.size shouldBe 1
    }

    @Test
    fun `INV-AC-20947 a second date answers the conflict PER-010 with the other date`() {
        val c = controller(person("u1"))
        post(c, "P", body("2026-04-28T20:00:00Z"))
        val (status, text) = post(c, "P", body("2026-04-30T20:00:00Z", "A2"))
        status shouldBe 200
        val json = mapper.readTree(text)
        json["governingFiledAt"].asString() shouldBe "2026-04-29T00:00:00+08:00"
        json["conflict"]["code"].asString() shouldBe "PER-010"
        json["conflict"]["otherFiledAt"].asString() shouldBe "2026-05-01T00:00:00+08:00"
        store.stored.size shouldBe 2
    }

    @Test
    fun `INV-AC-20947 a blank acknowledgement answers 400 and records nothing`() {
        val (status, text) = post(controller(person("u1")), "P", body("2026-04-28T20:00:00Z", " "))
        status shouldBe 400
        mapper.readTree(text)["code"].asString() shouldBe "SRT-001"
        store.stored.size shouldBe 0
        transactionEntries shouldBe 0
    }

    @Test
    fun `INV-AC-20947 a request without the acknowledgement member cannot be read`() {
        // The property is required by the contract: the framework's BindingFailureAdvice answers SRT-001 before the
        // controller; the model refuses to build without it.
        val failure =
            runCatching {
                mapper.readValue(
                    """{"filedAt":"2026-04-28T20:00:00Z"}""",
                    ConfirmFilingRequest::class.java,
                )
            }.exceptionOrNull()
        (failure != null) shouldBe true
        store.stored.size shouldBe 0
    }

    @Test
    fun `INV-AC-20947 a person without the key and a machine covering another entity answer 403`() {
        val u2 = post(controller(person("u2")), "P", body("2026-04-28T20:00:00Z"))
        u2.first shouldBe 403
        val m2 = post(controller(machine = machine("E2")), "P", body("2026-04-28T20:00:00Z"))
        m2.first shouldBe 403
        mapper.readTree(m2.second)["code"].asString() shouldBe "SRT-004"
        store.stored.size shouldBe 0
    }

    @Test
    fun `INV-AC-20947 a machine covering the entity answers 200 and the fact records the machine principal id`() {
        val m1 = machine("E")
        post(controller(machine = m1), "P", body("2026-04-28T20:00:00Z")).first shouldBe 200
        store.recordedBy shouldBe listOf(m1.id)
    }

    @Test
    fun `INV-AC-20947 a period without a due date answers 404 SRT-005`() {
        val (status, text) = post(controller(person("u1")), "P2", body("2026-04-28T20:00:00Z"))
        status shouldBe 404
        mapper.readTree(text)["code"].asString() shouldBe "SRT-005"
        store.stored.size shouldBe 0
    }

    @Test
    fun `INV-AC-20947 a period with two due dates answers 500 SRT-010 with no detail and records nothing`() {
        val (status, text) = post(controller(person("u1")), "P3", body("2026-04-28T20:00:00Z"))
        status shouldBe 500
        val json = mapper.readTree(text)
        json["code"].asString() shouldBe "SRT-010"
        json.has("detail") shouldBe false
        store.stored.size shouldBe 0
    }

    @Test
    fun `INV-AC-20947 a period not ready answers 422 PER-001 with severity BLOCK and disposition OVERRIDABLE`() {
        val (status, text) = post(controller(person("u1")), "P4", body("2026-04-28T20:00:00Z"))
        status shouldBe 422
        val json = mapper.readTree(text)
        json["code"].asString() shouldBe "PER-001"
        json["severity"].asString() shouldBe "BLOCK"
        json["disposition"].asString() shouldBe "OVERRIDABLE"
        envelopeErrors(text) shouldBe emptyList()
        store.stored.size shouldBe 0
    }

    /** The pinned error-envelope schema (unpacked by `unpackClientContracts`); unset or missing fails the case. */
    private fun envelopeErrors(text: String): List<String> {
        val dir = checkNotNull(System.getProperty("gst.client-contracts.dir")) { "gst.client-contracts.dir is not set" }
        val file = File(dir, "service-runtime/schemas/error-envelope.schema.json")
        check(file.isFile) { "the pinned error-envelope schema is missing: $file" }
        val schemas =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return schemas.getSchema(file.readText()).validate(text, InputFormat.JSON).map { it.toString() }
    }

    @Test
    fun `the envelope schema refuses a body without correlationId and a code that is a number`() {
        val (_, text) = post(controller(person("u1")), "P4", body("2026-04-28T20:00:00Z"))
        envelopeErrors(text) shouldBe emptyList()
        val tree = mapper.readTree(text) as tools.jackson.databind.node.ObjectNode
        val withoutCorrelation = tree.deepCopy().also { it.remove("correlationId") }
        envelopeErrors(mapper.writeValueAsString(withoutCorrelation)).isNotEmpty() shouldBe true
        val numericCode = tree.deepCopy().also { it.put("code", 1) }
        envelopeErrors(mapper.writeValueAsString(numericCode)).isNotEmpty() shouldBe true
    }

    @Test
    fun `the advice answers an ambiguous due date as SRT-010 500 and handles nothing broader`() {
        val response = FilingRefusalAdvice().ambiguous(AmbiguousDueDateException("secret two dates"))
        response.statusCode.value() shouldBe 500
        response.headers.getFirst("Content-Type") shouldBe "application/problem+json"
        val json = mapper.readTree(response.body!!)
        json["code"].asString() shouldBe "SRT-010"
        json.has("detail") shouldBe false
        response.body!! shouldNotContain "secret two dates"
        response.body!! shouldNotContain "AmbiguousDueDateException"
        val handled =
            FilingRefusalAdvice::class.java.declaredMethods
                .mapNotNull { it.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler::class.java) }
                .flatMap { it.value.toList() }
                .map { it.java }
                .toSet()
        handled shouldBe setOf(FilingPeriodRefusal::class.java, AmbiguousDueDateException::class.java)
    }

    @Test
    fun `INV-AC-20947 a request without a caller answers 401 SRT-003`() {
        val (status, text) = post(controller(), "P", body("2026-04-28T20:00:00Z"))
        status shouldBe 401
        mapper.readTree(text)["code"].asString() shouldBe "SRT-003"
    }

    @Test
    fun `each confirmFiling call enters exactly one inTransaction`() {
        val c = controller(person("u1"))
        post(c, "P", body("2026-04-28T20:00:00Z")).first shouldBe 200
        transactionEntries shouldBe 1
        post(c, "P4", body("2026-04-28T20:00:00Z")).first shouldBe 422
        transactionEntries shouldBe 2
    }

    @Test
    fun `the response never carries the property lateDocuments`() {
        val (_, text) = post(controller(person("u1")), "P", body("2026-04-28T20:00:00Z"))
        text shouldNotContain "lateDocuments"
    }
}

/** A use case no drain or readiness test calls: every port refuses, so a call would fail loudly. */
internal fun unusedConfirmFiling(): ConfirmFiling =
    ConfirmFiling(
        AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() }),
        FilingDueDates { _, _ -> error("confirmFiling is not under test") },
        GateModesAt { _, _ -> error("confirmFiling is not under test") },
        object : LatestChecks {
            override fun latest(
                entityRef: String,
                periodRef: String,
            ): LatestCheck? = error("confirmFiling is not under test")

            override fun knownCheckIds(
                entityRef: String,
                periodRef: String,
            ): Set<String> = error("confirmFiling is not under test")
        },
        ReleasedOverrideChecks { _, _ -> error("confirmFiling is not under test") },
        object : FilingFactStore {
            override fun facts(
                entityRef: String,
                periodRef: String,
            ): List<FilingFact> = error("confirmFiling is not under test")

            override fun append(fact: NewFilingFact) = error("confirmFiling is not under test")
        },
    )

/** A runner no drain or readiness test enters through `confirmFiling`. */
internal val unusedRunner: TransactionRunner =
    object : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }
