package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.events.AccreditationAlertsOutbox
import com.dynokore.gst.controlPlane.adapters.events.ControlPlaneEventCatalogue
import com.dynokore.gst.controlPlane.adapters.http.ProblemAdvice
import com.dynokore.gst.controlPlane.adapters.http.ProblemException
import com.dynokore.gst.controlPlane.adapters.http.ReleasesController
import com.dynokore.gst.controlPlane.adapters.http.TaskCursor
import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationRecordsJooq
import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationTaskRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.ReleaseAccreditationLinksJooq
import com.dynokore.gst.controlPlane.adapters.persistence.TrainRepositoryJooq
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlerts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationPermission
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepPorts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskQueries
import com.dynokore.gst.controlPlane.application.accreditation.ConcludeAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.GetAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.RecordAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseTarget
import com.dynokore.gst.controlPlane.application.accreditation.TaskCursorKey
import com.dynokore.gst.controlPlane.application.accreditation.TaskRead
import com.dynokore.gst.controlPlane.application.audit.AuditEntryPurger
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeAuthority
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeFloorSource
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntries
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.GateRunRepository
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.RecordGateRun
import com.dynokore.gst.controlPlane.application.release.ReleasePermission
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SupportWindowRepository
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.contract.model.AccreditationTask
import com.dynokore.gst.controlPlane.contract.model.ConcludeAccreditationTaskRequest
import com.dynokore.gst.controlPlane.contract.model.PublishTrainRequest
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Certification
import com.dynokore.gst.controlPlane.domain.release.DocumentId
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.Observation
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.RegenerationObservation
import com.dynokore.gst.controlPlane.domain.release.TrainEvidence
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.TransactionalOperationRunner
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
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
 * The accreditation step of the release pipeline against the real tables and the real outbox, as the runtime login
 * (INV-AC-2340): the release commits first, the task step has a transaction of its own.
 */
@Suppress("LargeClass") // one IT over the real tables for the step, the three operations and the rulings
class AccreditationTaskIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.now(clock)
    private val tenants = TenantContextImpl()
    private val transactions =
        TransactionRunnerImpl(TenantAwareDataSource(PostgresDb.runtimeDataSource), tenants, null, BINDING_KEY)

    /** The service's repositories: the platform-scoped tables they use, declared as service-runtime does at start. */
    private val dsl: DSLContext =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(
                    TABLES.associate {
                        DSL.name("controlplane", it) to TableClass.PLATFORM_SCOPED
                    },
                ),
                tenants,
                transactions,
            ).dsl()
    private val outbox =
        JdbcOutboxWriter(
            dsl,
            "controlplane",
            ControlPlaneEventCatalogue.SERVICE,
            "control-plane-svc-1",
            ControlPlaneEventCatalogue.catalogue(),
            clock,
        )
    private val runner =
        TransactionalOperationRunner(
            transactions,
            dsl,
            LocalAuditWriter(dsl, "controlplane", ControlPlaneEventCatalogue.SERVICE, "control-plane-svc-1", clock),
        )
    private val trains = TrainRepositoryJooq(dsl)
    private val taskRepository = AccreditationTaskRepositoryJooq(dsl)
    private val records = AccreditationRecordsJooq(dsl)
    private val corpus = mapOf(DocumentId("D1") to "SR")
    private val certified =
        TrainEvidence(
            Observation(GateOutcome.PASSED),
            Observation(GateOutcome.PASSED),
            RegenerationObservation(corpus, corpus),
            Certification(ReferenceEnvironment.entries.associateWith { GateOutcome.PASSED }),
            null,
        )
    private val permissions = OperatorPermissions { actor, key -> actor == "rita" && key == ReleasePermission.PUBLISH }
    private val publish = PublishTrain(trains, permissions, { certified }, clock)
    private val failures = ArrayList<Exception>()

    private fun stepWith(alerts: AccreditationAlerts = AccreditationAlertsOutbox(outbox)) =
        OpenAccreditationTask(
            AccreditationStepPorts(taskRepository, ReleaseAccreditationLinksJooq(dsl), alerts, records),
            transactions,
            clock,
            { _, failure -> failures += failure },
        )

    @BeforeEach
    fun reset() {
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
        admin("truncate controlplane.accreditation_task, controlplane.accreditation_record, controlplane.outbox")
    }

    private fun admin(sql: String) {
        PostgresDb.adminConnection().use { it.createStatement().use { s -> s.execute(sql) } }
    }

    private fun rows(sql: String): List<List<String?>> =
        PostgresDb.adminConnection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val out = ArrayList<List<String?>>()
                    while (rs.next()) out += (1..rs.metaData.columnCount).map { rs.getString(it) }
                    out
                }
            }
        }

    private fun accreditation(currentUntil: LocalDate) =
        inPlatform {
            records.save(
                AccreditationRecord(
                    today.minusDays(300),
                    currentUntil,
                    "IMDA-" + UUID.randomUUID(),
                    listOf(SubmissionType.TYPE_1A),
                    emptyList(),
                    clock.instant(),
                    ActorId("alice"),
                ),
            )
        }

    /** The release: its own transaction through the runner, as the controller does before the step. */
    private fun release(
        version: String,
        needs: Boolean,
    ): TrainVersion {
        val command =
            PublishTrainCommand(
                ActorId("rita"),
                TrainVersion(version),
                TrainKind.TRAIN,
                false,
                needs,
                RequestContext("corr-$version", UUID.randomUUID().toString()),
            )
        val outcome = tenants.bindPlatform("rita") { runner.run(publish, "rita", command) }
        (outcome is OperationOutcome.Performed) shouldBe true
        return command.version
    }

    private val defaultStep = stepWith()

    private fun <T> inPlatform(block: () -> T): T = tenants.bindPlatform("rita") { transactions.inTransaction(block) }

    private fun trainOf(version: String) = inPlatform { trains.find(TrainVersion(version)) }!!

    private fun afterRelease(
        version: String,
        needs: Boolean,
        step: OpenAccreditationTask = defaultStep,
    ) = tenants
        .bindPlatform("rita") {
            step.afterRelease(
                ReleaseTarget(ReleaseTarget.Kind.TRAIN, version),
                needs,
                RequestContext("corr-$version", "cause-$version"),
            )
        }.also { if (step === defaultStep) check(failures.isEmpty()) { failures.joinToString { it.toString() } } }

    private fun taskRows(version: String) =
        rows(
            "select task_ref, status, reason, alert_ref from controlplane.accreditation_task " +
                "where release_ref = '$version'",
        )

    private fun outboxRows() =
        rows("select type, tenant_id, entity_ref from controlplane.outbox where type like '%AccreditationAlertRaised%'")

    @Test
    fun `INV-AC-2340 a train that needs an assessment is published with one OPEN task and one platform event`() {
        val version = "2026." + UUID.randomUUID().toString().take(6)
        release(version, true)
        // the release is PUBLISHED and committed before the task step has run
        trainOf(version).accreditation shouldBe AccreditationCheck(true, null, null)
        taskRows(version) shouldBe emptyList()

        afterRelease(version, true)

        val task = taskRows(version).single()
        task[1] shouldBe "OPEN"
        task[2] shouldBe "REASSESSMENT_NEEDED"
        val event = outboxRows().single()
        event[0] shouldBe "com.dynokore.gst.platform.AccreditationAlertRaised.v1"
        event[1] shouldBe TenantId.PLATFORM.value.toString()
        event[2] shouldBe EntityRef.PLATFORM.value
        val payload = rows("select data::text from controlplane.outbox where type like '%AccreditationAlertRaised%'")
        payload.single()[0]!!.contains("\"alertRef\": \"${task[3]}\"") shouldBe true
        // the release record carries the task and the alert of the same release
        trainOf(version).accreditation shouldBe AccreditationCheck(true, task[3], task[0])
    }

    @Test
    fun `a lapsed accreditation with the request saying no opens exactly one lapse task`() {
        admin("truncate controlplane.accreditation_record")
        accreditation(today.minusDays(1))
        val version = "2026." + UUID.randomUUID().toString().take(6)
        release(version, false)
        afterRelease(version, false)
        val task = taskRows(version).single()
        task[2] shouldBe "ACCREDITATION_LAPSED"
        // the field holds the request's declaration, never whether a task was opened (INV-Q-15454 A)
        val check = trainOf(version).accreditation
        check.assessmentNeeded shouldBe false
        check.taskRef shouldBe task[0]
        check.alertRef shouldNotBe null
    }

    @Test
    fun `a current accreditation and an unrecorded one with no assessment open nothing`() {
        accreditation(today.plusDays(10))
        val current = "2026." + UUID.randomUUID().toString().take(6)
        release(current, false)
        afterRelease(current, false)
        taskRows(current) shouldBe emptyList()
        admin("truncate controlplane.accreditation_record")
        val unrecorded = "2026." + UUID.randomUUID().toString().take(6)
        release(unrecorded, false)
        afterRelease(unrecorded, false)
        taskRows(unrecorded) shouldBe emptyList()
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `a repeat of the same publication opens no second task`() {
        val version = "2026." + UUID.randomUUID().toString().take(6)
        release(version, true)
        val first = afterRelease(version, true)
        afterRelease(version, true) shouldBe first
        taskRows(version).size shouldBe 1
        outboxRows().size shouldBe 1
        // the table itself refuses a second task for the same release and reason
        shouldThrow<Exception> {
            admin(
                "insert into controlplane.accreditation_task(task_ref, status, reason, opened_at, alert_ref, " +
                    "release_ref) values ('T-${UUID.randomUUID()}', 'OPEN', 'REASSESSMENT_NEEDED', now(), " +
                    "'A-x', '$version')",
            )
        }
    }

    @Test
    fun `the unique constraint on release_ref and reason refuses a second task and the task checks hold`() {
        val version = "2026." + UUID.randomUUID().toString().take(6)

        fun insert(
            ref: String,
            status: String = "OPEN",
            reason: String = "REASSESSMENT_NEEDED",
            tail: String = "",
        ): String {
            val extraColumns = if (tail.isEmpty()) "" else ", concluded_at, conclusion"
            val extraValues = if (tail.isEmpty()) "" else ", $tail"
            return "insert into controlplane.accreditation_task(task_ref, status, reason, opened_at, alert_ref, " +
                "release_ref$extraColumns) values ('$ref', '$status', '$reason', now(), 'A-$ref', '$version'$extraValues)"
        }
        admin(insert("T-${UUID.randomUUID()}"))
        shouldThrow<Exception> { admin(insert("T-${UUID.randomUUID()}")) } // same (release_ref, reason)
        shouldThrow<Exception> { admin(insert("T-${UUID.randomUUID()}", status = "DONE")) }
        shouldThrow<Exception> { admin(insert("T-${UUID.randomUUID()}", reason = "OTHER")) }
        // OPEN with a conclusion, and CONCLUDED without one, are refused
        shouldThrow<Exception> {
            admin(
                insert("T-${UUID.randomUUID()}", reason = "ACCREDITATION_LAPSED", tail = "now(), 'x'"),
            )
        }
        shouldThrow<Exception> {
            admin(insert("T-${UUID.randomUUID()}", status = "CONCLUDED", reason = "ACCREDITATION_LAPSED"))
        }
        admin(
            insert(
                "T-${UUID.randomUUID()}",
                status = "CONCLUDED",
                reason = "ACCREDITATION_LAPSED",
                tail = "now(), 'done'",
            ),
        )
    }

    @Test
    fun `a forced exception in the task step leaves the release published and the step rolled back`() {
        val version = "2026." + UUID.randomUUID().toString().take(6)
        release(version, true)
        val failing = stepWith { _, _ -> error("forced") }
        afterRelease(version, true, failing) shouldBe null
        failures.size shouldBe 1
        trainOf(version).accreditation shouldBe AccreditationCheck(true, null, null) // still PUBLISHED
        taskRows(version) shouldBe emptyList() // the task row rolled back with the failed event
        outboxRows() shouldBe emptyList()
    }

    @Test
    fun `the runtime login cannot rewrite a task beyond its conclusion columns or delete one`() {
        val version = "2026." + UUID.randomUUID().toString().take(6)
        release(version, true)
        afterRelease(version, true)
        val ref = taskRows(version).single()[0]!!
        shouldThrow<DataAccessException> {
            PostgresDb.dsl().execute(
                "update controlplane.accreditation_task set reason = 'ACCREDITATION_LAPSED' where task_ref = '$ref'",
            )
        }
        shouldThrow<DataAccessException> {
            PostgresDb.dsl().execute("delete from controlplane.accreditation_task where task_ref = '$ref'")
        }
        inPlatform { taskRepository.conclude(ref, clock.instant(), "ok") }!!.conclusion shouldBe "ok"
    }

    // --- the operations as the operator calls them: the controller over the real tables
    private val held = mutableSetOf<String>()
    private val grants = OperatorPermissions { actor, key -> actor == "rita" && (key in held) }
    private var keyFile: ByteArray? = ByteArray(32) { 5 }
    private val cursorKey = TaskCursor { checkNotNull(keyFile) { "no cursor key file" } }
    private val publishAsRita = PublishTrain(trains, grants, { certified }, clock)

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not used by the accreditation operations")
        } as T

    private fun controller() =
        ReleasesController(
            EndUserPrincipalResolver { EndUserPrincipal("rita", null, emptySet()) },
            tenants,
            runner,
            publishAsRita,
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
            AccreditationTaskQueries(taskRepository, grants, transactions),
            ConcludeAccreditationTask(taskRepository, grants, clock),
            defaultStep,
            cursorKey,
        )

    private fun asOperator() {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                if (method.name == "getHeader" && args!![0] == "Authorization") "Bearer operator" else null
            } as HttpServletRequest
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @AfterEach
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    private fun refusal(
        status: Int,
        block: () -> Unit,
    ) {
        val problem = shouldThrow<ProblemException> { block() }.problem
        withClue(problem.body) { problem.status shouldBe status }
    }

    private fun publishOverHttp(
        version: String,
        needs: Boolean?,
    ) = run {
        asOperator()
        held += ReleasePermission.PUBLISH
        controller().publishTrain(
            UUID.randomUUID(),
            PublishTrainRequest(version, PublishTrainRequest.Kind.TRAIN, null, needs),
        )
    }

    @Test
    fun `an absent assessment field opens a task, a false one on a current accreditation opens none`() {
        accreditation(today.plusDays(10))
        val absent = "2026." + UUID.randomUUID().toString().take(6)
        val response = publishOverHttp(absent, null).body!!
        val task = taskRows(absent).single()
        task[2] shouldBe "REASSESSMENT_NEEDED"
        response.releaseGateResult!!.accreditationCheck!!.taskRef shouldBe task[0]
        val explicitFalse = "2026." + UUID.randomUUID().toString().take(6)
        publishOverHttp(explicitFalse, false)
            .body!!
            .releaseGateResult!!
            .accreditationCheck!!
            .taskRef shouldBe null
        taskRows(explicitFalse) shouldBe emptyList()
    }

    @Test
    fun `concludeAccreditationTask needs the conclude key, repeats on the same text and gives 409 on another`() {
        val version = "2026." + UUID.randomUUID().toString().take(6)
        publishOverHttp(version, true)
        val ref = taskRows(version).single()[0]!!

        fun conclude(text: String) =
            controller().concludeAccreditationTask(ref, UUID.randomUUID(), ConcludeAccreditationTaskRequest(text))
        asOperator()
        refusal(HTTP_FORBIDDEN) { conclude("reviewed") } // no key held: nothing stored
        taskRows(version).single()[1] shouldBe "OPEN"
        held += AccreditationPermission.CONCLUDE
        held += AccreditationPermission.TASK_VIEW
        val done = conclude("reviewed").body!!
        done.status shouldBe AccreditationTask.Status.CONCLUDED
        done.conclusion shouldBe "reviewed"
        done.concludedAt!!.toInstant() shouldBe clock.instant()
        conclude("reviewed").body shouldBe done
        val conflict = shouldThrow<ProblemException> { conclude("another") }.problem
        conflict.status shouldBe HTTP_CONFLICT
        conflict.body shouldContain "\"code\":\"CPL-007\""
        conflict.body shouldNotContain "SRT-008"
        controller().getAccreditationTask(ref).body!!.conclusion shouldBe "reviewed"
    }

    @Test
    fun `the list pages 120 tasks with limit 50 in three pages and returns each task_ref once`() {
        admin("truncate controlplane.accreditation_task")
        repeat(120) {
            val ref = "T-" + it.toString().padStart(3, '0')
            val openedAt = "timestamptz '2026-10-19 08:00:00+00' + interval '${it / 2} second'"
            admin(
                "insert into controlplane.accreditation_task(task_ref, status, reason, opened_at, alert_ref, " +
                    "release_ref) values ('$ref', 'OPEN', 'REASSESSMENT_NEEDED', $openedAt, 'A-$ref', 'R-$ref')",
            )
        }
        asOperator()
        held += AccreditationPermission.TASK_LIST
        val seen = ArrayList<String>()
        val sizes = ArrayList<Int>()
        val cursors = ArrayList<String?>()
        var cursor: String? = null
        do {
            val page = controller().listAccreditationTasks(null, cursor, 50).body!!
            sizes += page.items.size
            seen += page.items.map { it.taskRef }
            cursors += page.nextCursor
            cursor = page.nextCursor
        } while (cursor != null)
        sizes shouldBe listOf(50, 50, 20)
        cursors.last() shouldBe null // omitted on the last page
        seen.size shouldBe 120
        seen.toSet().size shouldBe 120
        seen.first() shouldBe "T-118" // opened_at descending, then task_ref ascending
        listOf(0, 501, -3).forEach { limit ->
            refusal(HTTP_BAD_REQUEST) { controller().listAccreditationTasks(null, null, limit) }
        }
        refusal(HTTP_BAD_REQUEST) { controller().listAccreditationTasks(null, cursors.first() + "x", 50) }
        refusal(HTTP_BAD_REQUEST) { controller().listAccreditationTasks("OPEN", cursors.first(), 50) } // another filter
    }

    @Test
    fun `list and get need their own keys and the record key opens neither`() {
        seedTasks(3)
        asOperator()
        held.clear()
        refusal(HTTP_FORBIDDEN) { controller().listAccreditationTasks(null, null, 50) }
        held += AccreditationPermission.RECORD // the record key does not open the list or the get
        refusal(HTTP_FORBIDDEN) { controller().listAccreditationTasks(null, null, 50) }
        refusal(HTTP_FORBIDDEN) { controller().getAccreditationTask("K-001") }
        held.clear()
        held += AccreditationPermission.TASK_LIST // the list key does not open the get
        refusal(HTTP_FORBIDDEN) { controller().getAccreditationTask("K-001") }
        held.clear()
        held += AccreditationPermission.TASK_VIEW
        controller().getAccreditationTask("K-001").body!!.taskRef shouldBe "K-001"
        refusal(HTTP_FORBIDDEN) { controller().listAccreditationTasks(null, null, 50) }
    }

    private fun seedTasks(count: Int) {
        admin("truncate controlplane.accreditation_task")
        repeat(count) {
            val ref = "K-" + it.toString().padStart(3, '0')
            admin(
                "insert into controlplane.accreditation_task(task_ref, status, reason, opened_at, alert_ref, " +
                    "release_ref) values ('$ref', 'OPEN', 'REASSESSMENT_NEEDED', now(), 'A-$ref', 'R-$ref')",
            )
        }
    }

    /** The list as the advice answers it: a status in 500..599 and a problem body with no items and no cursor. */
    private fun serverError(cursor: String?) {
        val failure = shouldThrow<IllegalStateException> { controller().listAccreditationTasks(null, cursor, 50) }
        val answer = ProblemAdvice().unexpected(failure)
        answer.statusCode.value() shouldBeInRange 500..599
        answer.body.shouldNotBeNull() shouldNotContain "items"
        answer.body.shouldNotBeNull() shouldNotContain "nextCursor"
    }

    @Test
    fun `with no cursor key file the list answers 500 for a result of one page and for one of several`() {
        asOperator()
        held += AccreditationPermission.TASK_LIST
        val keyed = ByteArray(32) { 5 }
        seedTasks(3)
        keyFile = null
        try {
            serverError(null) // one page
            seedTasks(120)
            serverError(null) // several pages
            serverError("x.y") // with a cursor, parsed only after the key is read
            keyFile = ByteArray(31) // a value shorter than 32 bytes is the same
            serverError(null)
        } finally {
            keyFile = keyed
        }
        controller().listAccreditationTasks(null, null, 50).body!!.nextCursor shouldNotBe null
    }

    @Test
    fun `a cursor signed with the old key file value gives 400 once the file holds another value`() {
        asOperator()
        held += AccreditationPermission.TASK_LIST
        seedTasks(120)
        val cursor = controller().listAccreditationTasks(null, null, 50).body!!.nextCursor!!
        controller()
            .listAccreditationTasks(null, cursor, 50)
            .body!!
            .items.size shouldBe 50
        keyFile = ByteArray(32) { 9 }
        refusal(HTTP_BAD_REQUEST) { controller().listAccreditationTasks(null, cursor, 50) }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409
        val TABLES =
            listOf(
                "release_train",
                "accreditation_task",
                "accreditation_record",
                "audit_entry",
                "outbox",
                "outbox_sequence",
            )

        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }
}
