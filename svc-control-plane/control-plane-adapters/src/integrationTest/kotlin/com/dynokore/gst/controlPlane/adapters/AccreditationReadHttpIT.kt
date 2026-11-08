package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.http.ProblemException
import com.dynokore.gst.controlPlane.adapters.http.ReleasesController
import com.dynokore.gst.controlPlane.adapters.http.TaskCursor
import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationRecordsJooq
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlerts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationPermission
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
import com.dynokore.gst.controlPlane.application.release.GateRunRepository
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.RecordGateRun
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SupportWindowRepository
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.contract.model.AccreditationStatus
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * getAccreditation through the controller against the real tables, as the runtime login (INV-AC-2338): the read runs
 * in its own transaction, answers 200 with the latest record and its currency, and is refused without the view key.
 */
class AccreditationReadHttpIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.now(clock)
    private val tenants = TenantContextImpl()
    private val transactions =
        TransactionRunnerImpl(TenantAwareDataSource(PostgresDb.runtimeDataSource), tenants, null, BINDING_KEY)
    private val dsl: DSLContext =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(
                    mapOf(DSL.name("controlplane", "accreditation_record") to TableClass.PLATFORM_SCOPED),
                ),
                tenants,
                transactions,
            ).dsl()
    private val records = AccreditationRecordsJooq(dsl)
    private val held = mutableSetOf<String>()
    private val grants = OperatorPermissions { actor, key -> actor == "rita" && key in held }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not used by getAccreditation")
        } as T

    private fun controller() =
        ReleasesController(
            EndUserPrincipalResolver { EndUserPrincipal("rita", null, emptySet()) },
            tenants,
            unused<OperationRunner>(),
            PublishTrain(unused<TrainRepository>(), grants, unused<TrainEvidenceSource>(), clock),
            SetSupportWindow(unused<SupportWindowRepository>(), grants),
            RecordAccreditation(records, grants, clock),
            GetAccreditation(records, grants, clock),
            PurgeAuditEntries(
                unused<AuditPurgeAuthority>(),
                unused<AuditPurgeFloorSource>(),
                unused<AuditEntryPurger>(),
                clock,
            ),
            RecordGateRun(unused<GateRunRepository>(), unused<TrainRepository>(), grants, clock),
            AccreditationTaskQueries(unused<AccreditationTaskRepository>(), grants, transactions),
            ConcludeAccreditationTask(unused<AccreditationTaskRepository>(), grants, clock),
            OpenAccreditationTask(
                AccreditationStepPorts(
                    unused<AccreditationTaskRepository>(),
                    unused<ReleaseAccreditationLinks>(),
                    unused<AccreditationAlerts>(),
                    records,
                    unused<AccreditationStepQueue>(),
                ),
                transactions,
                clock,
                unused<StepReporting>(),
            ),
            TaskCursor { ByteArray(32) },
            transactions,
        )

    private fun asOperator() {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                if (method.name == "getHeader" && args!![0] == "Authorization") "Bearer operator" else null
            } as HttpServletRequest
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @BeforeEach
    fun reset() {
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
        PostgresDb.adminConnection().use {
            it.createStatement().use { s -> s.execute("truncate controlplane.accreditation_record") }
        }
        held.clear()
        asOperator()
    }

    @AfterEach
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    private fun store(currentUntil: LocalDate): AccreditationRecord {
        val record =
            AccreditationRecord(
                today.minusDays(300),
                currentUntil,
                "IMDA-" + UUID.randomUUID(),
                listOf(SubmissionType.TYPE_1A),
                emptyList(),
                clock.instant(),
                ActorId("alice"),
            )
        tenants.bindPlatform("rita") { transactions.inTransaction { records.save(record) } }
        return record
    }

    @Test
    fun `INV-AC-2338 a stored record is answered 200 with that record and CURRENT`() {
        val stored = store(today.plusDays(30))
        held += AccreditationPermission.READ
        val response = controller().getAccreditation()
        response.statusCode.value() shouldBe HTTP_OK
        response.body!!.currency shouldBe AccreditationStatus.Currency.CURRENT
        response.body!!.record!!.imdaReference shouldBe stored.imdaReference
    }

    @Test
    fun `INV-AC-2338 no stored record is answered 200 with NOT_RECORDED and no record`() {
        held += AccreditationPermission.READ
        val response = controller().getAccreditation()
        response.statusCode.value() shouldBe HTTP_OK
        response.body!!.currency shouldBe AccreditationStatus.Currency.NOT_RECORDED
        response.body!!.record shouldBe null
    }

    @Test
    fun `INV-AC-2338 a caller without the view key is answered 403 and nothing is read`() {
        store(today.plusDays(30))
        held += AccreditationPermission.RECORD // the write key does not read
        val problem = shouldThrow<ProblemException> { controller().getAccreditation() }.problem
        withClue(problem.body) { problem.status shouldBe HTTP_FORBIDDEN }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403

        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }
}
