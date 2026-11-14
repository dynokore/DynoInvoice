package com.dynokore.gst.ingestion.bootstrap

import com.dynokore.gst.ingestion.adapters.http.AdministrationController
import com.dynokore.gst.ingestion.adapters.http.ApiRefusal
import com.dynokore.gst.ingestion.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.ingestion.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.ingestion.adapters.persistence.jooq.tables.TriggerCoverage.Companion.TRIGGER_COVERAGE
import com.dynokore.gst.ingestion.application.permission.CallerSource
import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.gst.ingestion.contract.model.TriggerMappingRequest
import com.dynokore.gst.ingestion.contract.model.TriggerMappingResult
import com.dynokore.gst.ingestion.contract.model.TriggerWaiverRequest
import com.dynokore.gst.ingestion.contract.model.TriggerWaiverResult
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * INV-AC-20939 (INV-REQ-788) against a real database (P109): `mapTriggerClass` and `waiveTriggerClass` of the full
 * TPL-18 context, called through the controller bean with a recording [EntityPermission] that permits user U1 only and
 * a [CallerSource] that returns U1, U2 or refuses as unauthenticated.
 */
class TriggerCoverageOperationsTest {
    private val u1 = UUID.randomUUID()
    private val u2 = UUID.randomUUID()
    private val tenant = UUID.randomUUID()
    private val worker = "svc:ingestion-svc"
    private val adapter = "ERP-ACME-PULL"
    private val review = LocalDate.parse("2027-07-01")

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    private inner class Service(
        context: ApplicationContext,
        val calls: MutableList<Triple<String, String, String>>,
        val caller: AtomicReference<String?>,
        val clock: MutableClock,
    ) {
        val controller: AdministrationController = context.getBean(AdministrationController::class.java)
        private val scope: TenantScope = context.getBean(TenantScope::class.java)
        private val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        private val dsl: DSLContext = context.getBean(DSLContext::class.java)
        private val advice = ApiRefusalAdvice()

        /** The row state of both tables, in the tenant's transaction. */
        fun state(): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> =
            scope.bind(TenantId(tenant), worker) {
                runner.inTransaction {
                    dsl.selectFrom(TRIGGER_COVERAGE).fetchMaps() to dsl.selectFrom(Outbox.OUTBOX).fetchMaps()
                }
            }

        fun <T> asTenant(block: () -> T): T = scope.bind(TenantId(tenant), worker, block)

        /** The call as [user] (null: no caller); a refusal is rendered by the advice, as on the wire. */
        fun <T> call(
            user: UUID?,
            block: () -> T,
        ): Result<T> {
            caller.set(user?.toString())
            return try {
                Result.success(asTenant(block))
            } catch (refusal: ApiRefusal) {
                Result.failure(refusal)
            }
        }

        fun refusal(result: Result<*>): Pair<Int, String> {
            val response = advice.refuse(shouldThrow<ApiRefusal> { result.getOrThrow() })
            return response.statusCode.value() to response.body.orEmpty()
        }

        fun map(
            user: UUID?,
            entity: String,
            triggerClass: String,
            adapterRef: String = adapter,
        ) = call(user) {
            controller.mapTriggerClass(
                entity,
                triggerClass,
                UUID.randomUUID(),
                TriggerMappingRequest(adapterRef),
            )
        }

        fun waive(
            user: UUID?,
            entity: String,
            triggerClass: String,
            request: TriggerWaiverRequest,
        ) = call(user) { controller.waiveTriggerClass(entity, triggerClass, UUID.randomUUID(), request) }
    }

    private fun run(
        dir: Path,
        block: Service.() -> Unit,
    ) {
        val calls = CopyOnWriteArrayList<Triple<String, String, String>>()
        val caller = AtomicReference<String?>()
        val clock = MutableClock(Instant.parse("2026-11-14T10:00:00Z"))
        IngestionContext
            .online(dir)
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .withBean(Clock::class.java, { clock })
            .withBean(
                EntityPermission::class.java,
                {
                    EntityPermission { actor, entity, key ->
                        calls += Triple(actor, entity, key)
                        actor == u1.toString()
                    }
                },
            ).withBean(
                CallerSource::class.java,
                { CallerSource { caller.get() ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED) } },
            ).run { context -> Service(context, calls, caller, clock).block() }
    }

    private fun covered(evidence: String?) =
        TriggerWaiverRequest(
            TriggerWaiverRequest.Mode.COVERED_BY_OTHER_SOLUTION,
            coveringSolution = "Manual register",
            evidenceReference = evidence,
        )

    private val reviewWaiver get() = TriggerWaiverRequest(TriggerWaiverRequest.Mode.REVIEW_WAIVER, reviewDate = review)

    @Test
    fun `INV-AC-20939 U1 maps a class, answers 200 with the mapping and the repeat answers the same body`(
        @TempDir dir: Path,
    ) = run(dir) {
        val first = map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED").getOrThrow()
        first.statusCode.value() shouldBe 200
        val body: TriggerMappingResult = first.body!!
        body.entityRef shouldBe "E-001"
        body.triggerClass shouldBe "OUTBOUND_DOCUMENT_ISSUED"
        body.adapterRef shouldBe adapter
        clock.advance(Duration.ofHours(1))
        val repeat = map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED").getOrThrow()
        repeat.statusCode.value() shouldBe 200
        repeat.body shouldBe body
        val (coverage, outbox) = state()
        coverage.size shouldBe 1
        outbox.size shouldBe 1
        coverage.single()[TRIGGER_COVERAGE.RECORDED_BY.name] shouldBe u1
    }

    @Test
    fun `INV-AC-20939 U1 waives with a review date and records covered elsewhere with evidence`(
        @TempDir dir: Path,
    ) = run(dir) {
        val waived: TriggerWaiverResult =
            waive(u1, "E-001", "THIRD_PARTY_ISSUANCE", reviewWaiver).getOrThrow().body!!
        waived.mode shouldBe TriggerWaiverResult.Mode.REVIEW_WAIVER
        waived.reviewDate shouldBe review
        val covered: TriggerWaiverResult =
            waive(u1, "E-001", "INBOUND_DOCUMENT_RECORDED", covered("EV-1")).getOrThrow().body!!
        covered.mode shouldBe TriggerWaiverResult.Mode.COVERED_BY_OTHER_SOLUTION
        covered.evidenceReference shouldBe "EV-1"
        val (coverage, outbox) = state()
        coverage.size shouldBe 2
        outbox.size shouldBe 2
        coverage.map { it[TRIGGER_COVERAGE.RECORDED_BY.name] }.toSet() shouldBe setOf(u1)
    }

    @Test
    fun `INV-AC-20939 covered elsewhere without an evidence reference is refused and stores nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val before = state()
        val (status, body) = refusal(waive(u1, "E-001", "INBOUND_DOCUMENT_RECORDED", covered(null)))
        status shouldBe 400
        body shouldContain "SRT-001"
        state() shouldBe before
    }

    @Test
    fun `INV-AC-20939 U2 is refused with 403 SRT-004 on both operations and nothing is stored or published`(
        @TempDir dir: Path,
    ) = run(dir) {
        val before = state()
        listOf(
            refusal(map(u2, "E-001", "OUTBOUND_DOCUMENT_ISSUED")),
            refusal(waive(u2, "E-001", "THIRD_PARTY_ISSUANCE", reviewWaiver)),
        ).forEach { (status, body) ->
            status shouldBe 403
            body shouldContain "SRT-004"
        }
        state() shouldBe before
    }

    @Test
    fun `INV-AC-20939 an unauthenticated caller is refused with 401 SRT-003 on both operations`(
        @TempDir dir: Path,
    ) = run(dir) {
        val before = state()
        listOf(
            refusal(map(null, "E-001", "OUTBOUND_DOCUMENT_ISSUED")),
            refusal(waive(null, "E-001", "THIRD_PARTY_ISSUANCE", reviewWaiver)),
        ).forEach { (status, body) ->
            status shouldBe 401
            body shouldContain "SRT-003"
        }
        state() shouldBe before
        calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-20939 the permission calls carry U1, the entityRef of the path and the scoped key`(
        @TempDir dir: Path,
    ) = run(dir) {
        map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED").getOrThrow()
        waive(u1, "E-002", "THIRD_PARTY_ISSUANCE", reviewWaiver).getOrThrow()
        waive(u1, "E-002", "INBOUND_DOCUMENT_RECORDED", covered("EV-1")).getOrThrow()
        calls.toList() shouldBe
            listOf(
                Triple(u1.toString(), "E-001", "entity-config.manage"),
                Triple(u1.toString(), "E-002", "entity-config.manage"),
                Triple(u1.toString(), "E-002", "vp-61.set"),
            )
    }

    @Test
    fun `a different content for a recorded class answers 409 SRT-008 and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED").getOrThrow()
        val before = state()
        val (status, body) = refusal(map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED", "OTHER-ADAPTER"))
        status shouldBe 409
        body shouldContain "SRT-008"
        body shouldNotContain adapter
        body shouldNotContain "recordedAt"
        state() shouldBe before
    }

    @Test
    fun `the repeat answers the stored time`(
        @TempDir dir: Path,
    ) = run(dir) {
        val first = map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED").getOrThrow().body!!
        val waiver1 = waive(u1, "E-001", "THIRD_PARTY_ISSUANCE", reviewWaiver).getOrThrow().body!!
        clock.advance(Duration.ofDays(2))
        val second = map(u1, "E-001", "OUTBOUND_DOCUMENT_ISSUED").getOrThrow().body!!
        val waiver2 = waive(u1, "E-001", "THIRD_PARTY_ISSUANCE", reviewWaiver).getOrThrow().body!!
        second.mappedAt.toString() shouldBe first.mappedAt.toString()
        waiver2.waivedAt.toString() shouldBe waiver1.waivedAt.toString()
    }
}
