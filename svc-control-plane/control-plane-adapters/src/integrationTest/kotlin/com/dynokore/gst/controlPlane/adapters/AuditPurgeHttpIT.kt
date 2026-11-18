package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.access.AuthoriseClient
import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorPermissions
import com.dynokore.gst.controlPlane.adapters.audit.AuditPurgeStoreJooq
import com.dynokore.gst.controlPlane.adapters.audit.UndeterminedRetentionFloorSource
import com.dynokore.gst.controlPlane.adapters.http.ProblemException
import com.dynokore.gst.controlPlane.adapters.http.ReleasesController
import com.dynokore.gst.controlPlane.adapters.http.TaskCursor
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlerts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationRecords
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepPorts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepQueue
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskQueries
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskRepository
import com.dynokore.gst.controlPlane.application.accreditation.ConcludeAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.GetAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.RecordAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseAccreditationLinks
import com.dynokore.gst.controlPlane.application.accreditation.StepReporting
import com.dynokore.gst.controlPlane.application.audit.AuditEntryPurger
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeAuthority
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeFloorSource
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntries
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.GateRunRepository
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.RecordGateRun
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SupportWindowRepository
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.contract.model.PurgeAuditEntriesRequest
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The purge operation as the operator calls it (INV-AC-3823): the controller, the use case, the live `authorise`
 * binding (a stub access-svc over HTTP) and the purge function of `controlplane.audit_entry`, as the runtime login.
 * The request is the one a servlet container hands the controller: its headers and its body.
 */
class AuditPurgeHttpIT {
    private val clock = Clock.systemUTC()
    private val tenants = TenantContextImpl()
    private val runner =
        TransactionRunnerImpl(TenantAwareDataSource(PostgresDb.runtimeDataSource), tenants, null, BINDING_KEY)
    private val dsl: DSLContext = TenancyJooq.dslContext(emptyMap(), tenants, runner)

    /** The Idempotency-Key table as the service declares it to the tenant layer (platform keys: platform tenant). */
    private val idempotencyDsl: DSLContext =
        TenancyJooq.dslContext(mapOf(DSL.name("controlplane", "idempotency_key") to TableClass.TENANT), tenants, runner)
    private val stager: AuditStager =
        LocalAuditWriter(dsl, "controlplane", "control-plane-svc", "control-plane-svc-1", clock)
    private val store = AuditPurgeStoreJooq(dsl, stager)

    /** The permission strings the stub access-svc answers ALLOW for; any other question is DENY. */
    private val allowed = mutableSetOf<String>()
    private var stubStatus = 200
    private var stubDelayMillis = 0L
    private val asked = mutableListOf<String>()
    private val access: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(AuthoriseClient.PATH) { exchange ->
                val query =
                    exchange.requestURI.rawQuery.split("&").associate {
                        it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
                    }
                val permission = query.getValue("permission")
                asked += "$permission|${query["scope"]}"
                Thread.sleep(stubDelayMillis)
                val body =
                    if (permission in allowed && query["scope"] == "platform") {
                        """{"decision":"ALLOW","role":"platform.engineer"}"""
                    } else {
                        """{"decision":"DENY"}"""
                    }
                exchange.sendResponseHeaders(stubStatus, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }

    @BeforeEach
    fun reset() {
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
        admin("truncate controlplane.audit_entry")
        admin("delete from controlplane.idempotency_key")
        allowed.clear()
        allowed += KEY
    }

    @AfterEach
    fun stop() {
        access.stop(0)
        RequestContextHolder.resetRequestAttributes()
    }

    private fun admin(sql: String) {
        PostgresDb.adminConnection().use { it.createStatement().use { s -> s.execute(sql) } }
    }

    private fun adminText(sql: String): String? =
        PostgresDb.adminConnection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun count() = adminText("select count(*) from controlplane.audit_entry")!!.toLong()

    private fun seed(recordedAt: Instant) =
        admin(
            "insert into controlplane.audit_entry (id, tenant_id, entity_ref, recorded_at, source, subject, " +
                "instance_id, correlation_id, causation_id, actor, action, scope) values ('${UUID.randomUUID()}', " +
                "'$PLATFORM', 'e', '${recordedAt.atOffset(ZoneOffset.UTC)}', '/gst/control-plane-svc', 's', 'i', " +
                "'c', 'c', 'operator-1', 'grantRole', 'x')",
        )

    private fun yearsAgo(years: Long): Instant = Instant.now().minus(years * DAYS_PER_YEAR, ChronoUnit.DAYS)

    private fun livePermissions(timeout: Duration) =
        AuthoriseOperatorPermissions(
            AuthoriseClient(
                URI.create("http://127.0.0.1:${access.address.port}"),
                { WorkloadToken("workload-secret") },
                { "Bearer operator-secret" },
                timeout,
            ),
        )

    @Suppress("LongMethod") // one constructor call that lists every collaborator of the controller
    private fun controller(
        floors: AuditPurgeFloorSource,
        timeout: Duration = Duration.ofSeconds(2),
        principal: String = ACTOR,
    ): ReleasesController {
        val permissions = livePermissions(timeout)
        val authority =
            AuditPurgeAuthority {
                actor,
                key,
                scope,
                ->
                scope == "platform" && permissions.holds(actor, key)
            }
        val purger =
            AuditEntryPurger { actor, cutOff, reason, context ->
                runner.inTransaction { store.purge(actor, cutOff, reason, context) }
            }
        val useCase = PurgeAuditEntries(authority, floors, purger, clock)
        val noPermission = OperatorPermissions { _, _ -> false }
        return ReleasesController(
            EndUserPrincipalResolver { EndUserPrincipal(principal, null, emptySet()) },
            tenants,
            unused<OperationRunner>(),
            PublishTrain(
                unused<TrainRepository>(),
                noPermission,
                unused<TrainEvidenceSource>(),
                clock,
            ),
            SetSupportWindow(unused<SupportWindowRepository>(), noPermission),
            RecordAccreditation(unused<AccreditationRecords>(), noPermission, clock),
            GetAccreditation(unused<AccreditationRecords>(), noPermission, clock),
            useCase,
            RecordGateRun(unused<GateRunRepository>(), unused<TrainRepository>(), noPermission, clock),
            AccreditationTaskQueries(unused<AccreditationTaskRepository>(), noPermission, runner),
            ConcludeAccreditationTask(unused<AccreditationTaskRepository>(), noPermission, clock),
            OpenAccreditationTask(
                AccreditationStepPorts(
                    unused<AccreditationTaskRepository>(),
                    unused<ReleaseAccreditationLinks>(),
                    unused<AccreditationAlerts>(),
                    unused<AccreditationRecords>(),
                    unused<AccreditationStepQueue>(),
                ),
                unused<TransactionRunner>(),
                clock,
                unused<StepReporting>(),
            ),
            TaskCursor { ByteArray(32) },
            runner,
            IdempotencyStore(TenantScope(tenants, runner, runner, "control-plane-svc"), idempotencyDsl, "controlplane"),
        )
    }

    private fun asRequest(headers: Map<String, String>) {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                when (method.name) {
                    "getHeader" -> headers[args!![0] as String]
                    "getMethod" -> "POST"
                    "getRequestURI" -> "/v1/audit-entries/purge"
                    else -> error("not used: ${method.name}")
                }
            } as HttpServletRequest
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    private fun call(
        controller: ReleasesController,
        cutOff: Instant,
        key: UUID = UUID.randomUUID(),
        reason: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = run {
        asRequest(mapOf("Authorization" to "Bearer operator-secret") + extraHeaders)
        controller.purgeAuditEntries(key, PurgeAuditEntriesRequest(cutOff.atOffset(ZoneOffset.UTC), reason))
    }

    private fun refusal(
        status: Int,
        code: String,
        block: () -> Unit,
    ) {
        val problem = shouldThrow<ProblemException> { block() }.problem
        problem.status shouldBe status
        problem.body shouldContain "\"code\":\"$code\""
    }

    @Test
    fun `INV-AC-3823 a caller without audit-entry purge gets SRT-004 and nothing is deleted`() {
        seed(yearsAgo(YEARS_11))
        val floors = AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS)) }
        // DENY, an error status and a timeout
        allowed.clear()
        refusal(HTTP_FORBIDDEN, "SRT-004") { call(controller(floors), yearsAgo(YEARS_11 - 1)) }
        allowed += KEY
        stubStatus = HTTP_ERROR
        refusal(HTTP_FORBIDDEN, "SRT-004") { call(controller(floors), yearsAgo(YEARS_11 - 1)) }
        stubStatus = HTTP_OK
        stubDelayMillis = DELAY_MILLIS
        refusal(HTTP_FORBIDDEN, "SRT-004") {
            call(controller(floors, Duration.ofMillis(TIMEOUT_MILLIS)), yearsAgo(YEARS_11 - 1))
        }
        stubDelayMillis = 0
        // ALLOW only for the look-alikes
        allowed.clear()
        allowed += listOf("audit-entries.purge", "$KEY ")
        refusal(HTTP_FORBIDDEN, "SRT-004") { call(controller(floors), yearsAgo(YEARS_11 - 1)) }
        count() shouldBe 1
    }

    @Test
    fun `INV-AC-3823 a cut-off younger than the floor gets CPL-001 and nothing is deleted`() {
        seed(yearsAgo(YEARS_11))
        val floors = AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS), Period.ofYears(2)) }
        refusal(HTTP_UNPROCESSABLE, "CPL-001") { call(controller(floors), yearsAgo(SIX - 1)) }
        count() shouldBe 1
    }

    @Test
    fun `INV-AC-3823 the bound floor source gives CPL-002 and deletes nothing`() {
        seed(yearsAgo(YEARS_11))
        refusal(HTTP_UNAVAILABLE, "CPL-002") {
            call(controller(UndeterminedRetentionFloorSource()), yearsAgo(YEARS_11 - 1))
        }
        count() shouldBe 1
    }

    @Test
    fun `INV-AC-3823 the floor is the maximum of the regions and the purge answers the five fields`() {
        seed(yearsAgo(YEARS_4))
        seed(yearsAgo(YEARS_7))
        seed(yearsAgo(YEARS_11))
        val floors = AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS), Period.ofYears(2)) }
        val cutOff = yearsAgo(SIX + 1)
        val keyOne = UUID.randomUUID()
        val first = call(controller(floors), cutOff, keyOne, reason = "retention review").body!!
        first.deletedCount shouldBe 2
        first.floor shouldBe "P6Y"
        first.cutOff.toInstant().epochSecond shouldBe cutOff.epochSecond
        first.purgeId shouldNotBe first.auditEntryId
        adminText("select reason from controlplane.audit_entry where id = '${first.auditEntryId}'") shouldBe
            "retention review"
        adminText("select action from controlplane.audit_entry where id = '${first.auditEntryId}'") shouldBe
            "purgeAuditEntries"
        adminText("select actor from controlplane.audit_entry where id = '${first.auditEntryId}'") shouldBe ACTOR
        count() shouldBe 2
        val second = call(controller(floors), cutOff, UUID.randomUUID()).body!!
        second.deletedCount shouldBe 0
        second.purgeId shouldNotBe first.purgeId
        second.auditEntryId shouldNotBe first.auditEntryId
    }

    @Test
    fun `INV-AC-3823 without a body reason the entry holds the Idempotency-Key and the request correlation id`() {
        seed(yearsAgo(YEARS_11))
        val floors = AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS)) }
        val key = UUID.randomUUID()
        val cutOff = yearsAgo(SIX + 1)
        val answer =
            call(controller(floors), cutOff, key, null, mapOf("X-Correlation-Id" to "corr-from-request"))
                .body!!
        val id = answer.auditEntryId
        adminText("select reason from controlplane.audit_entry where id = '$id'") shouldBe key.toString()
        adminText("select correlation_id from controlplane.audit_entry where id = '$id'") shouldBe "corr-from-request"
        adminText("select causation_id from controlplane.audit_entry where id = '$id'") shouldBe key.toString()
        // a blank reason is no reason (another key: the same key with another body is a different request, SRT-009)
        val blankKey = UUID.randomUUID()
        val blank = call(controller(floors), cutOff, blankKey, "   ").body!!
        adminText("select reason from controlplane.audit_entry where id = '${blank.auditEntryId}'") shouldBe
            blankKey.toString()
    }

    private fun keyRows() = adminText("select count(*) from controlplane.idempotency_key")!!.toLong()

    private fun seedForReplay(): AuditPurgeFloorSource {
        seed(yearsAgo(YEARS_11))
        seed(yearsAgo(YEARS_7))
        return AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS)) }
    }

    @Test
    fun `INV-AC-24824 the same key and body replays the first status and body with no second purge`() {
        val floors = seedForReplay()
        val cutOff = yearsAgo(SIX + 1)
        val key = UUID.randomUUID()
        val first = call(controller(floors), cutOff, key, "retention review")
        val rowsAfterFirst = count()
        val idsAfterFirst = adminText("select string_agg(id::text, ',' order by id) from controlplane.audit_entry")
        val replay = call(controller(floors), cutOff, key, "retention review")
        replay.statusCode shouldBe first.statusCode
        replay.body shouldBe first.body
        count() shouldBe rowsAfterFirst
        adminText("select string_agg(id::text, ',' order by id) from controlplane.audit_entry") shouldBe idsAfterFirst
        keyRows() shouldBe 1
    }

    @Test
    fun `INV-AC-24824 the same key with another cut-off is 422 SRT-009 and changes nothing`() {
        val floors = seedForReplay()
        val key = UUID.randomUUID()
        call(controller(floors), yearsAgo(SIX + 1), key)
        val rows = count()
        refusal(HTTP_UNPROCESSABLE, "SRT-009") { call(controller(floors), yearsAgo(SIX + 2), key) }
        count() shouldBe rows
        keyRows() shouldBe 1
    }

    @Test
    fun `INV-AC-24824 a second call with the key while the first is held open is 409 SRT-007 and changes nothing`() {
        seed(yearsAgo(YEARS_11))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held =
            AuditPurgeFloorSource {
                entered.countDown()
                release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                listOf(Period.ofYears(SIX_YEARS))
            }
        val key = UUID.randomUUID()
        val cutOff = yearsAgo(SIX + 1)
        val firstDone = CountDownLatch(1)
        val worker =
            thread {
                try {
                    call(controller(held), cutOff, key)
                } finally {
                    RequestContextHolder.resetRequestAttributes()
                    firstDone.countDown()
                }
            }
        try {
            entered.await(HOLD_SECONDS, TimeUnit.SECONDS) shouldBe true
            refusal(HTTP_CONFLICT, "SRT-007") { call(controller(held), cutOff, key) }
            count() shouldBe 1
        } finally {
            release.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(HOLD_SECONDS))
        }
        firstDone.await(HOLD_SECONDS, TimeUnit.SECONDS) shouldBe true
        count() shouldBe 1 // the held call finished its own purge: the deleted row stays gone, its entry exists
        keyRows() shouldBe 1
    }

    @Test
    fun `INV-AC-24824 the same key under another principal runs the purge again`() {
        val floors = seedForReplay()
        val cutOff = yearsAgo(SIX + 1)
        val key = UUID.randomUUID()
        val first = call(controller(floors), cutOff, key).body!!
        val other = call(controller(floors, principal = "operator-10"), cutOff, key).body!!
        other.auditEntryId shouldNotBe first.auditEntryId
        other.purgeId shouldNotBe first.purgeId
        keyRows() shouldBe 2
    }

    @Test
    fun `INV-AC-24824 a 5xx response is not stored so the retry runs again`() {
        seed(yearsAgo(YEARS_11))
        val key = UUID.randomUUID()
        val cutOff = yearsAgo(SIX + 1)
        refusal(HTTP_UNAVAILABLE, "CPL-002") { call(controller(UndeterminedRetentionFloorSource()), cutOff, key) }
        keyRows() shouldBe 0
        val retry = call(controller(AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS)) }), cutOff, key)
        retry.body!!.deletedCount shouldBe 1
        keyRows() shouldBe 1
    }

    @Test
    fun `INV-AC-24824 a stored refusal below 500 is replayed with its status and code`() {
        seed(yearsAgo(YEARS_11))
        val floors = AuditPurgeFloorSource { listOf(Period.ofYears(SIX_YEARS)) }
        val key = UUID.randomUUID()
        val cutOff = yearsAgo(SIX - 1)
        refusal(HTTP_UNPROCESSABLE, "CPL-001") { call(controller(floors), cutOff, key) }
        keyRows() shouldBe 1
        refusal(HTTP_UNPROCESSABLE, "CPL-001") { call(controller(floors), cutOff, key) }
        count() shouldBe 1
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not used by the purge")
        } as T

    private companion object {
        const val ACTOR = "operator-9"
        const val KEY = "audit-entry.purge"
        val PLATFORM: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        const val DAYS_PER_YEAR = 366L
        const val YEARS_4 = 4L
        const val YEARS_7 = 7L
        const val YEARS_11 = 11L
        const val SIX = 6L
        const val SIX_YEARS = 6
        const val HTTP_OK = 200
        const val HTTP_ERROR = 500
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_UNAVAILABLE = 503
        const val HTTP_CONFLICT = 409
        const val HOLD_SECONDS = 20L
        const val DELAY_MILLIS = 1500L
        const val TIMEOUT_MILLIS = 200L
        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }
}
