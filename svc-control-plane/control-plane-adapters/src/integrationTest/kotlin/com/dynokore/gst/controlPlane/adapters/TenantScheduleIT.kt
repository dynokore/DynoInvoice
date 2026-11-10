package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.events.ControlPlaneEventCatalogue
import com.dynokore.gst.controlPlane.adapters.http.ProblemException
import com.dynokore.gst.controlPlane.adapters.http.TenantScheduleEndpoint
import com.dynokore.gst.controlPlane.adapters.persistence.TenantScheduleRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.schedule.ScheduleJobAuditWriter
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.GetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.OpenDueTenants
import com.dynokore.gst.controlPlane.application.schedule.SchedulePermission
import com.dynokore.gst.controlPlane.application.schedule.ScheduleRefusal
import com.dynokore.gst.controlPlane.application.schedule.SetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.SetTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.TenantOpening
import com.dynokore.gst.controlPlane.contract.model.ApproveTenantScheduleRequest
import com.dynokore.gst.controlPlane.contract.model.ChangeTenantScheduleRequest
import com.dynokore.gst.controlPlane.contract.model.RetentionCustodyHolder
import com.dynokore.gst.controlPlane.contract.model.SetTenantScheduleRequest
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.TransactionalOperationRunner
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The tenant schedule against the real table, the real operation runner and the real audit writer, as the runtime
 * login (INV-AC-8101, INV-AC-8102 opening part, INV-AC-8103): the audit entry commits with the step.
 */
@Suppress("LargeClass") // one fixture shared by the HTTP and persistence cases of five criteria
class TenantScheduleIT {
    private val goLive = Instant.parse("2026-12-01T01:00:00Z")
    private val offboarding = Instant.parse("2027-12-01T01:00:00Z")
    private var now = Instant.parse("2026-11-20T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant() = now
        }
    private val tenants = TenantContextImpl()
    private val transactions =
        TransactionRunnerImpl(TenantAwareDataSource(PostgresDb.runtimeDataSource), tenants, null, BINDING_KEY)
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(TABLES.associate { DSL.name("controlplane", it) to TableClass.PLATFORM_SCOPED }),
                tenants,
                transactions,
            ).dsl()
    private val audit =
        LocalAuditWriter(dsl, "controlplane", ControlPlaneEventCatalogue.SERVICE, "control-plane-svc-1", clock)
    private val runner = TransactionalOperationRunner(transactions, dsl, audit)
    private val schedules = TenantScheduleRepositoryJooq(dsl)
    private val seq =
        java.util.concurrent.atomic
            .AtomicInteger()
    private val allKeys =
        setOf(
            SchedulePermission.SET,
            SchedulePermission.CHANGE,
            SchedulePermission.CANCEL,
            SchedulePermission.READ,
            SchedulePermission.APPROVE,
        )
    private val grants = mutableMapOf("alice" to allKeys, "bob" to allKeys)
    private val permissions = OperatorPermissions { actor, key -> key in grants[actor].orEmpty() }
    private val set = SetTenantSchedule(schedules, permissions, clock) { "SCH-IT${seq.incrementAndGet()}" }
    private val approve = ApproveTenantSchedule(schedules, permissions, clock)
    private val change = ChangeTenantSchedule(schedules, permissions, clock)
    private val cancel = CancelTenantSchedule(schedules, permissions)
    private val opens = ArrayList<String>()
    private val job =
        OpenDueTenants(
            schedules,
            { t, s ->
                opens += "${t.value}/$s"
                TenantOpening.OPENED
            },
            ScheduleJobAuditWriter(audit),
            transactions,
            clock,
        )
    private val tenant = CommercialRef("TEN-IT1")

    @BeforeEach
    fun reset() {
        seq.set(0)
        PostgresDb.adminConnection().use {
            it.createStatement().use { s ->
                s.execute("truncate controlplane.tenant_schedule, controlplane.audit_entry, controlplane.outbox")
            }
        }
    }

    /** The job runs without a person under the platform binding, as TenantScheduleOpeningJob does. */
    private fun runJob() = tenants.bindPlatform("control-plane-svc") { job.runOnce(10) }

    private fun request() =
        RequestContext(
            "corr",
            java.util.UUID
                .randomUUID()
                .toString(),
        )

    private fun setSchedule(
        actor: String,
        contract: String? = "CTR-1",
        off: Instant = offboarding,
    ) = tenants.bindPlatform(actor) {
        runner.run(
            set,
            actor,
            SetTenantScheduleCommand(
                ActorId(actor),
                tenant,
                contract,
                goLive,
                off,
                CustodyHolder("Custodian", "c@x.example", null),
                null,
                request(),
            ),
        )
    }

    private fun approveBy(
        actor: String,
        target: ApproveTenantScheduleCommand.Target = ApproveTenantScheduleCommand.Target.SCHEDULE,
    ) = tenants.bindPlatform(actor) {
        runner.run(approve, actor, ApproveTenantScheduleCommand(ActorId(actor), tenant, target, request()))
    }

    private fun audits() =
        PostgresDb.adminConnection().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select actor, action, reason from controlplane.audit_entry order by recorded_at, id",
                    ).use { rs ->
                        val out = ArrayList<Triple<String, String, String?>>()
                        while (rs.next()) out += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                        out
                    }
            }
        }

    private fun stored() = tenants.bindPlatform("alice") { transactions.inTransaction { schedules.find(tenant) } }

    @Test
    fun `INV-AC-8101 approval by the operator or by a holder without the key is refused and the real row stays`() {
        (setSchedule("alice") is OperationOutcome.Performed) shouldBe true
        (setSchedule("alice", contract = null) as OperationOutcome.PreconditionUnmet).code shouldBe
            "SCHEDULE_CONTRACT_MISSING"
        (approveBy("alice") as OperationOutcome.PreconditionUnmet).code shouldBe ScheduleRefusal.SELF_APPROVAL
        approveBy("carol") shouldBe OperationOutcome.NotPermitted
        stored()?.state shouldBe ScheduleState.AWAITING_APPROVAL
        audits().map { it.second } shouldBe listOf("setTenantSchedule")
        (approveBy("bob") is OperationOutcome.Performed) shouldBe true
        stored()?.approvedBy shouldBe "bob"
        val approval = audits().single { it.second == "approveTenantSchedule" }
        approval.first shouldBe "bob"
        approval.third shouldBe "tenant=TEN-IT1 contract=CTR-1"
    }

    @Test
    fun `INV-AC-8102 the job opens an approved tenant once at go-live and its audit entry names the job`() {
        setSchedule("alice")
        approveBy("bob")
        now = goLive.minusSeconds(1)
        runJob() shouldBe 0
        now = goLive
        runJob() shouldBe 1
        runJob() shouldBe 0
        opens shouldBe listOf("TEN-IT1/SCH-IT1")
        stored()?.openedAt shouldBe goLive
        audits().single { it.second == "openTenant" }.first shouldBe OpenDueTenants.ACTOR
    }

    @Test
    fun `INV-AC-8103 a change and a cancellation of the real row wait for the second holder`() {
        setSchedule("alice")
        approveBy("bob")
        val newOff = offboarding.plusSeconds(86400)
        tenants.bindPlatform("alice") {
            runner.run(
                change,
                "alice",
                ChangeTenantScheduleCommand(ActorId("alice"), tenant, "SCH-IT1", null, newOff, request()),
            )
        }
        tenants.bindPlatform("alice") {
            runner.run(cancel, "alice", CancelTenantScheduleCommand(ActorId("alice"), tenant, request()))
        }
        stored()?.offboardingAt shouldBe offboarding
        (
            approveBy(
                "alice",
                ApproveTenantScheduleCommand.Target.CHANGE,
            ) as OperationOutcome.PreconditionUnmet
        ).code shouldBe
            ScheduleRefusal.SELF_APPROVAL
        (approveBy("bob", ApproveTenantScheduleCommand.Target.CHANGE) is OperationOutcome.Performed) shouldBe true
        stored()?.offboardingAt shouldBe newOff
        (approveBy("bob", ApproveTenantScheduleCommand.Target.CANCELLATION) is OperationOutcome.Performed) shouldBe true
        stored()?.state shouldBe ScheduleState.CANCELLED
    }

    private val endpoint =
        TenantScheduleEndpoint(
            EndUserPrincipalResolver { header ->
                header?.removePrefix("Bearer ")?.let { EndUserPrincipal(it, null, emptySet()) }
            },
            tenants,
            transactions,
            runner,
            set,
            approve,
            change,
            cancel,
            GetTenantSchedule(schedules, permissions),
        )

    /** The HTTP answer of [call] as "status code" (or "200"), for the caller [who]. */
    private fun answer(
        who: String,
        call: () -> Any,
    ): String {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                if (method.name == "getHeader" && args?.get(0) == "Authorization") "Bearer $who" else null
            } as HttpServletRequest
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        return try {
            call()
            "200"
        } catch (e: ProblemException) {
            val code = Regex("\"code\":\"([A-Z]+-[0-9]+)\"").find(e.problem.body)?.groupValues?.get(1)
            "${e.problem.status} $code"
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    private fun setBody(
        contract: String = "CTR-1",
        off: Instant = offboarding,
        at: Instant = goLive,
    ) = SetTenantScheduleRequest(
        contract,
        at.atOffset(ZoneOffset.UTC),
        off.atOffset(ZoneOffset.UTC),
        RetentionCustodyHolder("Custodian", "c@x.example"),
    )

    private fun httpSet(
        who: String,
        body: SetTenantScheduleRequest = setBody(),
    ) = answer(who) { endpoint.set(tenant.value, UUID.randomUUID(), body) }

    private fun httpChange(
        who: String,
        off: Instant,
    ) = answer(who) {
        endpoint.change(
            tenant.value,
            UUID.randomUUID(),
            ChangeTenantScheduleRequest("SCH-IT1", null, off.atOffset(ZoneOffset.UTC)),
        )
    }

    private fun httpCancel(who: String) = answer(who) { endpoint.cancel(tenant.value, UUID.randomUUID()) }

    private fun httpApprove(
        who: String,
        target: ApproveTenantScheduleRequest.Target = ApproveTenantScheduleRequest.Target.SCHEDULE,
    ) = answer(who) { endpoint.approve(tenant.value, UUID.randomUUID(), ApproveTenantScheduleRequest(target)) }

    private fun rows() =
        PostgresDb.adminConnection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from controlplane.tenant_schedule").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `INV-AC-19761 over HTTP each operation answers only the actor allowed its key, others get 403`() {
        val keyOf =
            mapOf<String, (String) -> String>(
                SchedulePermission.SET to { w -> httpSet(w) },
                SchedulePermission.APPROVE to { w -> httpApprove(w) },
                SchedulePermission.CHANGE to { w -> httpChange(w, offboarding.plusSeconds(60)) },
                SchedulePermission.CANCEL to { w -> httpCancel(w) },
                SchedulePermission.READ to { w -> answer(w) { endpoint.get(tenant.value) } },
            )
        // an actor the role port would report as commercial.admin holds no schedule key: refused 403 SRT-004
        for ((key, call) in keyOf) {
            grants["roleOnly"] = emptySet()
            call("roleOnly") shouldBe "403 SRT-004"
            for (other in allKeys - key) {
                grants["onlyOther"] = setOf(other)
                call("onlyOther") shouldBe "403 SRT-004"
            }
            for (alike in listOf(key + "s", key.replaceFirstChar { it.uppercase() })) {
                grants["alike"] = setOf(alike)
                call("alike") shouldBe "403 SRT-004"
            }
        }
        rows() shouldBe 0
        grants["setter"] = setOf(SchedulePermission.SET)
        httpSet("setter") shouldBe "200"
        rows() shouldBe 1
        grants["reader"] = setOf(SchedulePermission.READ)
        answer("reader") { endpoint.get(tenant.value) } shouldBe "200"
        httpApprove("setter") shouldBe "403 SRT-004"
        stored()?.state shouldBe ScheduleState.AWAITING_APPROVAL
    }

    @Test
    fun `INV-AC-19762 over HTTP the refusals carry CPL-011 with 422 and CPL-010 with 409`() {
        httpSet("alice", setBody(contract = " ")) shouldBe "422 CPL-011"
        httpSet("alice", setBody(off = goLive)) shouldBe "422 CPL-011"
        httpSet("alice", setBody(at = now)) shouldBe "422 CPL-011"
        rows() shouldBe 0
        httpSet("alice") shouldBe "200"
        httpSet("alice") shouldBe "200" // the same contract reference returns the original schedule
        rows() shouldBe 1
        httpSet("alice", setBody(contract = "CTR-2")) shouldBe "409 CPL-010"
        httpApprove("bob") shouldBe "200"
        httpChange("alice", offboarding.plusSeconds(60)) shouldBe "200"
        httpChange("bob", offboarding.plusSeconds(120)) shouldBe "409 CPL-010"
        httpCancel("alice") shouldBe "200"
        httpCancel("bob") shouldBe "409 CPL-010"
        rows() shouldBe 1
        stored()?.pendingChange?.offboardingAt shouldBe offboarding.plusSeconds(60)
        // a carried-out go-live time cannot be changed
        now = goLive
        runJob() shouldBe 1
        answer("alice") {
            endpoint.change(
                tenant.value,
                UUID.randomUUID(),
                ChangeTenantScheduleRequest("SCH-IT1", goLive.plusSeconds(3600).atOffset(ZoneOffset.UTC), null),
            )
        } shouldBe "409 CPL-010"
    }

    @Test
    fun `INV-AC-19762 a set after a cancellation is accepted and an absent contractReference fails the schema`() {
        httpSet("alice") shouldBe "200"
        httpApprove("bob") shouldBe "200"
        httpCancel("alice") shouldBe "200"
        httpApprove("bob", ApproveTenantScheduleRequest.Target.CANCELLATION) shouldBe "200"
        stored()?.state shouldBe ScheduleState.CANCELLED
        httpSet("alice", setBody(contract = "CTR-2")) shouldBe "200"
        rows() shouldBe 2
        stored()?.contractReference?.value shouldBe "CTR-2"
        val mapper =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .findAndAddModules()
                .build()
        val absent =
            """{"goLiveAt":"2026-12-01T01:00:00Z","offboardingAt":"2027-12-01T01:00:00Z",""" +
                """"retentionCustodyHolder":{"name":"n","contactEmail":"e@x"}}"""
        val present = absent.replace("{\"goLiveAt\"", "{\"contractReference\":\"CTR-9\",\"goLiveAt\"")
        mapper.readValue(present, SetTenantScheduleRequest::class.java).contractReference shouldBe "CTR-9"
        runCatching { mapper.readValue(absent, SetTenantScheduleRequest::class.java) }.isFailure shouldBe true
    }

    @Test
    fun `INV-AC-19762 change and cancel of an awaiting schedule answer 409 and the approval re-check keeps the row`() {
        httpSet("alice") shouldBe "200"
        val before = stored()
        httpChange("alice", offboarding.plusSeconds(60)) shouldBe "409 CPL-010"
        httpCancel("alice") shouldBe "409 CPL-010"
        stored() shouldBe before
        httpApprove("bob") shouldBe "200"
        // the go-live time of the pending change passes before the approval: 409 CPL-010, never 422
        val pendingGoLive = now.plusSeconds(3600)
        answer("alice") {
            endpoint.change(
                tenant.value,
                UUID.randomUUID(),
                ChangeTenantScheduleRequest("SCH-IT1", pendingGoLive.atOffset(ZoneOffset.UTC), null),
            )
        } shouldBe "200"
        val pending = stored()
        now = pendingGoLive
        httpApprove("bob", ApproveTenantScheduleRequest.Target.CHANGE) shouldBe "409 CPL-010"
        stored() shouldBe pending
    }

    @Test
    fun `INV-AC-8102 the fair order takes a never attempted row first and failing rows do not starve it`() {
        for (t in listOf("TEN-A", "TEN-B", "TEN-C")) seedApproved(CommercialRef(t))
        now = goLive
        val failing = setOf("TEN-A", "TEN-B")
        val calls = ArrayList<String>()
        val failingJob =
            OpenDueTenants(
                schedules,
                { t, _ ->
                    calls += t.value
                    if (t.value in failing) TenantOpening.NOT_OPENED else TenantOpening.OPENED
                },
                ScheduleJobAuditWriter(audit),
                transactions,
                clock,
            )
        tenants.bindPlatform("control-plane-svc") { failingJob.runOnce(2) }
        calls shouldBe listOf("TEN-A", "TEN-B")
        calls.clear()
        now = goLive.plusSeconds(60)
        tenants.bindPlatform("control-plane-svc") { failingJob.runOnce(2) }
        // the third row (never attempted) precedes the rows attempted before; the opened row is not returned again
        calls shouldBe listOf("TEN-C", "TEN-A")
        calls.clear()
        tenants.bindPlatform("control-plane-svc") { failingJob.runOnce(3) }
        calls.contains("TEN-C") shouldBe false
    }

    /** Sets the schedule of [tenantRef] by alice and approves it by bob, through the real runner. */
    private fun seedApproved(tenantRef: CommercialRef) {
        tenants.bindPlatform("alice") {
            runner.run(
                set,
                "alice",
                SetTenantScheduleCommand(
                    ActorId("alice"),
                    tenantRef,
                    "CTR-${tenantRef.value}",
                    goLive,
                    offboarding,
                    CustodyHolder("Custodian", "c@x.example", null),
                    null,
                    request(),
                ),
            )
        }
        tenants.bindPlatform("bob") {
            runner.run(
                approve,
                "bob",
                ApproveTenantScheduleCommand(
                    ActorId("bob"),
                    tenantRef,
                    ApproveTenantScheduleCommand.Target.SCHEDULE,
                    request(),
                ),
            )
        }
    }

    private companion object {
        val TABLES = listOf("tenant_schedule", "audit_entry", "outbox", "outbox_sequence")

        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }

    init {
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
    }
}
