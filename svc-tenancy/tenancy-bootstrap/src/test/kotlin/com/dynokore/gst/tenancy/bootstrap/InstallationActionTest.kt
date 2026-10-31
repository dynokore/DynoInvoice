package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.InstallationController
import com.dynokore.gst.tenancy.adapters.http.InstallerCallFilter
import com.dynokore.gst.tenancy.contract.model.InstallationActionAck
import com.dynokore.gst.tenancy.contract.model.InstallationFacts
import com.dynokore.gst.tenancy.contract.model.InstallationFactsBackupTarget
import com.dynokore.gst.tenancy.contract.model.InstallationFactsHeartbeatTarget
import com.dynokore.gst.tenancy.contract.model.RecordInstallationActionRequest
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.util.ContentCachingRequestWrapper
import java.time.OffsetDateTime
import java.util.UUID

private typealias Cmd = RecordInstallationActionRequest.Command
private typealias Outcome = RecordInstallationActionRequest.Outcome

/**
 * recordInstallationAction through the real application context over PostgreSQL 17 and 18 (INV-T-3290; INV-REQ-1101
 * b5, INV-REQ-1107, INV-REQ-1116, INV-REQ-861, INV-Q-3245 A, INV-Q-3826 A). Test names carry the criterion key.
 */
class InstallationActionTest {
    private val secret = "pg-Secret-7731"
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private val t0 = OffsetDateTime.parse("2026-10-20T08:00:00Z")
    private val noHeartbeat = InstallationFactsHeartbeatTarget(false)

    @Suppress("LongParameterList")
    private fun step(
        run: UUID,
        n: Int,
        command: Cmd,
        name: String,
        minute: Long,
        facts: InstallationFacts? = null,
        trainFrom: String? = null,
        trainTo: String? = null,
    ) = RecordInstallationActionRequest(
        run,
        n,
        command,
        name,
        Outcome.SUCCEEDED,
        t0.plusMinutes(minute),
        "cli:ops-lee",
        "ops-lee",
        null,
        trainFrom,
        trainTo,
        facts,
    )

    private fun send(
        ctx: AssertableWebApplicationContext,
        request: RecordInstallationActionRequest,
    ): InstallationActionAck =
        ctx.getBean(InstallationController::class.java).recordInstallationAction(UUID.randomUUID(), request).body!!

    private fun train(
        value: String,
        window: String? = null,
    ) = InstallationFacts(releaseTrain = value, supportedWindow = window)

    private fun count(
        db: BoundaryDatabase,
        sql: String,
        vararg args: String,
    ): Long =
        db.asSuperuser { c ->
            c.prepareStatement(sql).use { s ->
                args.forEachIndexed { i, a -> s.setString(i + 1, a) }
                s.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun actions(
        db: BoundaryDatabase,
        runs: List<UUID>,
    ) = runs.sumOf { count(db, "select count(*) from tenancy.installation_action where run_id = ?::uuid", "$it") }

    private fun entries(
        db: BoundaryDatabase,
        runs: List<UUID>,
    ) = runs.sumOf {
        count(
            db,
            "select count(*) from tenancy.outbox where tenant_id = '$platform' and type like '%AuditEntryRecorded%' " +
                "and data::text like ?",
            "%runId=$it %",
        )
    }

    private fun fact(
        db: BoundaryDatabase,
        key: String,
    ): String? =
        db.asSuperuser { c ->
            c.prepareStatement("select fact_value from tenancy.installation_fact where fact_key = ?").use { s ->
                s.setString(1, key)
                s.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    private fun differing(ctx: AssertableWebApplicationContext) =
        ctx.getBean(MeterRegistry::class.java).counter("tenancy.installation_action.differing_repeat").count()

    private fun run(
        db: BoundaryDatabase,
        block: (AssertableWebApplicationContext) -> Unit,
    ) {
        db.asSuperuser { c -> c.createStatement().use { it.execute("delete from tenancy.installation_fact") } }
        db.runner().withPropertyValues("inv.installer.subject=installer-sa").run { ctx ->
            ctx.startupFailure?.let { throw it }
            block(ctx)
        }
    }

    private fun backup(at: Long) = InstallationFactsBackupTarget(true, t0.plusMinutes(at))

    private fun installSteps(run: UUID) =
        listOf(
            step(run, 1, Cmd.INSTALL, "verify-backup-target", 1, InstallationFacts(backupTarget = backup(1))),
            step(run, 2, Cmd.INSTALL, "record-heartbeat-target", 2, InstallationFacts(heartbeatTarget = noHeartbeat)),
            step(run, 3, Cmd.INSTALL, "apply-release-train", 3, train("2026.10", "2026.10 to 2027.04")),
            step(run, 4, Cmd.INSTALL, "health-check", 4),
        )

    private fun upgradeSteps(run: UUID) =
        listOf(
            step(
                run,
                1,
                Cmd.UPGRADE,
                "pre-flight",
                10,
                InstallationFacts(backupTarget = backup(10)),
                "2026.10",
                "2026.11",
            ),
            step(run, 2, Cmd.UPGRADE, "apply-release-train", 11, train("2026.11"), "2026.10", "2026.11"),
            step(run, 3, Cmd.UPGRADE, "regeneration-check", 12, trainFrom = "2026.10", trainTo = "2026.11"),
        )

    private fun restoreSteps(run: UUID) =
        listOf(
            step(run, 1, Cmd.RESTORE, "restore-database", 20),
            step(run, 2, Cmd.RESTORE, "apply-release-train", 21, train("2026.10.1"), "2026.11", "2026.10.1"),
        )

    private fun renamed(step: RecordInstallationActionRequest) = step.copy(stepName = "renamed")

    /** The scenario of INV-AC-3828: install, upgrade, repeats, a restore to the backup point, re-sends. */
    private fun scenario(db: BoundaryDatabase) {
        run(db) { ctx ->
            val (install, upgrade, restore) = List(3) { UUID.randomUUID() }
            val installed = installSteps(install)
            val upgraded = upgradeSteps(upgrade)
            assertThat(installed.map { send(ctx, it).auditEntryId }.distinct()).hasSize(4)
            val backupSeq = count(db, "select coalesce(max(seq), 0) from tenancy.outbox")
            upgraded.forEach { send(ctx, it) }
            assertThat(fact(db, "releaseTrain")).isEqualTo("2026.11")
            // all seven again, then one under a changed step name: nothing changes, the counter rises by one
            val before = differing(ctx)
            (installed + upgraded).forEach { send(ctx, it) }
            assertThat(differing(ctx)).isEqualTo(before)
            assertThat(send(ctx, renamed(installed[2])).auditEntryId).isEqualTo(send(ctx, installed[2]).auditEntryId)
            assertThat(differing(ctx)).isEqualTo(before + 1)
            assertThat(actions(db, listOf(install, upgrade))).isEqualTo(7)
            assertThat(entries(db, listOf(install, upgrade))).isEqualTo(7)
            rewind(db, upgrade, backupSeq, install)
            assertThat(entries(db, listOf(install, upgrade))).isEqualTo(4)
            (restoreSteps(restore) + upgraded).forEach { send(ctx, it) }
            val runs = listOf(install, upgrade, restore)
            assertThat(actions(db, runs)).isEqualTo(9)
            assertThat(entries(db, runs)).isEqualTo(9)
            assertThat(fact(db, "releaseTrain")).isEqualTo("2026.10.1")
            assertThat(named(db, runs)).isEqualTo(9)
            assertNoSecret(db)
        }
    }

    /** Rows naming the identity that ran the command and the named deployment.operator. */
    private fun named(
        db: BoundaryDatabase,
        runs: List<UUID>,
    ) = runs.sumOf {
        count(
            db,
            "select count(*) from tenancy.installation_action where run_id = ?::uuid " +
                "and deployment_operator = 'ops-lee' and run_identity = 'cli:ops-lee'",
            "$it",
        )
    }

    /** What a restore to the backup point leaves: the rows written after it are gone, the facts are as they were. */
    private fun rewind(
        db: BoundaryDatabase,
        upgrade: UUID,
        backupSeq: Long,
        install: UUID,
    ) {
        db.asSuperuser { c ->
            c.createStatement().use {
                it.execute("delete from tenancy.installation_action where run_id = '$upgrade'")
                it.execute("delete from tenancy.outbox where tenant_id = '$platform' and seq > $backupSeq")
                it.execute(
                    "update tenancy.outbox_sequence set last_value = 4 " +
                        "where tenant_id = '$platform' and aggregate_key = 'installation'",
                )
                it.execute(
                    "update tenancy.installation_fact set fact_value = '2026.10', recorded_at = '${t0.plusMinutes(
                        3,
                    )}' where fact_key = 'releaseTrain'",
                )
            }
        }
        assertThat(actions(db, listOf(install))).isEqualTo(4)
    }

    private fun assertNoSecret(db: BoundaryDatabase) {
        assertThat(db.outboxRowsMentioning(secret)).isZero()
        assertThat(
            count(db, "select count(*) from tenancy.installation_action a where a::text like ?", "%$secret%"),
        ).isZero()
        assertThat(
            count(db, "select count(*) from tenancy.installation_fact f where f::text like ?", "%$secret%"),
        ).isZero()
    }

    @Test
    fun `INV-AC-3828 each run-record step leaves one audit entry after repeats and a restore on PostgreSQL 17`() {
        scenario(BoundaryFixture)
    }

    @Test
    fun `INV-AC-3828 each run-record step leaves one audit entry after repeats and a restore on PostgreSQL 18`() {
        scenario(BoundaryFixture18)
    }

    @Test
    fun `INV-AC-3828 a step sent three times leaves one entry and one set of facts and look-alikes write their own`() {
        run(BoundaryFixture) { ctx ->
            val db = BoundaryFixture
            val runA = UUID.randomUUID()
            val runB = UUID.randomUUID()
            val facts =
                InstallationFacts(
                    "2026.10",
                    "window",
                    InstallationFactsBackupTarget(true, t0, "bucket ok"),
                    InstallationFactsHeartbeatTarget(true, "monitor.example.org"),
                )
            val first = step(runA, 1, Cmd.INSTALL, "one", 1, facts)
            val acks = List(3) { send(ctx, first) }
            assertThat(acks.map { it.auditEntryId }.distinct()).hasSize(1)
            assertThat(entries(db, listOf(runA))).isEqualTo(1)
            assertThat(actions(db, listOf(runA))).isEqualTo(1)
            assertThat(count(db, "select count(*) from tenancy.installation_fact")).isEqualTo(4)
            assertThat(
                fact(db, "backupTarget"),
            ).isEqualTo("""{"verified":true,"checkedAt":"2026-10-20T08:00:00Z","detail":"bucket ok"}""")
            assertThat(
                fact(db, "heartbeatTarget"),
            ).isEqualTo("""{"present":true,"endpointHost":"monitor.example.org"}""")
            val otherStep = send(ctx, step(runA, 2, Cmd.INSTALL, "two", 2))
            val otherRun = send(ctx, step(runB, 1, Cmd.INSTALL, "one", 1, facts))
            assertThat(setOf(acks[0].auditEntryId, otherStep.auditEntryId, otherRun.auditEntryId)).hasSize(3)
            assertThat(entries(db, listOf(runA, runB))).isEqualTo(3)
            assertThat(actions(db, listOf(runA, runB))).isEqualTo(3)
            assertThat(count(db, "select count(*) from tenancy.installation_fact")).isEqualTo(4)
        }
    }

    @Test
    fun `INV-AC-3828 the audit entry names the actor the operator the action the scope and the prior state`() {
        run(BoundaryFixture) { ctx ->
            val db = BoundaryFixture
            val run = UUID.randomUUID()
            send(ctx, step(run, 1, Cmd.INSTALL, "apply", 1, train("2026.10")))
            send(ctx, step(run, 2, Cmd.UPGRADE, "apply", 2, train("2026.11")))
            val data =
                db.asSuperuser { c ->
                    c
                        .prepareStatement(
                            "select data::text from tenancy.outbox where tenant_id = '$platform' " +
                                "and data::text like ? order by seq desc limit 1",
                        ).use { s ->
                            s.setString(1, "%runId=$run step=2%")
                            s.executeQuery().use {
                                it.next()
                                it.getString(1)
                            }
                        }
                }
            assertThat(
                data,
            ).contains(
                "\"actor\": \"cli:ops-lee\"",
                "installation.UPGRADE",
                "\"scope\": \"installation\"",
                "deploymentOperator=ops-lee",
                "\"releaseTrain\": \"2026.10\"",
            )
        }
    }

    @Test
    fun `INV-AC-3828 an unknown request field is listed in warnings and never stored`() {
        run(BoundaryFixture) { ctx ->
            val request = step(UUID.randomUUID(), 1, Cmd.INSTALL, "one", 1, train("2026.10"))
            val body = """{"runId":"${request.runId}","inputs":{"pgPassword":"$secret"},"facts":{"extra":1}}"""
            val http =
                ContentCachingRequestWrapper(
                    MockHttpServletRequest().also { it.setContent(body.toByteArray()) },
                    65_536,
                )
            http.inputStream.readAllBytes()
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(http))
            try {
                assertThat(send(ctx, request).warnings).containsExactly("facts.extra", "inputs")
            } finally {
                RequestContextHolder.resetRequestAttributes()
            }
            assertNoSecret(BoundaryFixture)
        }
    }

    private fun filterOutcome(
        verification: (WorkloadToken?) -> WorkloadVerification,
        subject: String,
        configure: (MockHttpServletRequest) -> Unit = {},
        path: String = InstallerCallFilter.PATH,
    ): Pair<Int, Boolean> {
        val filter = InstallerCallFilter(WorkloadTokenVerifier(verification), subject)
        val request =
            MockHttpServletRequest("POST", path).also {
                it.requestURI = path
                configure(it)
            }
        val response = MockHttpServletResponse()
        var passed = false
        filter.doFilter(request, response, FilterChain { _, _ -> passed = true })
        return response.status to passed
    }

    @Test
    fun `INV-AC-3828 only the installer workload identity is accepted and every other call is refused`() {
        val installer = { _: WorkloadToken? -> WorkloadVerification.Verified("installer-sa") }
        val token = { r: MockHttpServletRequest -> r.addHeader(WorkloadToken.HEADER_NAME, "t") }
        assertThat(filterOutcome(installer, "installer-sa", token)).isEqualTo(200 to true)
        // another workload identity, a look-alike subject, a prefix, a case variant: SRT-004
        listOf("other-sa", "installer-sa2", "installer-s", "Installer-SA", "installer-sa ").forEach { subject ->
            assertThat(
                filterOutcome({ WorkloadVerification.Verified(subject) }, "installer-sa", token),
            ).isEqualTo(403 to false)
        }
        // no valid workload token: SRT-003
        assertThat(filterOutcome({ WorkloadVerification.Refused("missing") }, "installer-sa")).isEqualTo(401 to false)
        // the installer token with an end-user token beside it: SRT-004
        assertThat(
            filterOutcome(installer, "installer-sa", {
                token(it)
                it.addHeader("Authorization", "Bearer u")
            }),
        ).isEqualTo(403 to false)
        // an unconfigured installer subject refuses every caller, also one whose subject is blank
        assertThat(filterOutcome(installer, "", token)).isEqualTo(403 to false)
        assertThat(filterOutcome({ WorkloadVerification.Verified("") }, "", token)).isEqualTo(403 to false)
    }

    @Test
    fun `INV-AC-3828 the refusal body is the API-20 envelope with the SRT code`() {
        val filter = InstallerCallFilter({ WorkloadVerification.Verified("other-sa") }, "installer-sa")
        val request =
            MockHttpServletRequest("POST", InstallerCallFilter.PATH).also {
                it.requestURI =
                    InstallerCallFilter.PATH
                ; it.addHeader(WorkloadToken.HEADER_NAME, "t")
            }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> })
        assertThat(response.contentAsString).contains("SRT-004")
        assertThat(response.getHeader("Content-Type")).contains("problem+json")
    }
}
