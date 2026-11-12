package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqReplayCandidateSource
import com.dynokore.gst.submission.adapters.persistence.JooqReplayRequeue
import com.dynokore.gst.submission.adapters.persistence.JooqReplayVersionLookup
import com.dynokore.gst.submission.application.replay.ReplayActions
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayBySelectionRequest
import com.dynokore.gst.submission.application.replay.ReplayBySelectionResult
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.ReplaySingleVersionRequest
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ReplayWiringTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    @Test
    fun `INV-AC-20588 the ReplayBySelection bean is built over the adapters and decides with no grant fail closed`() {
        val dsl = DSL.using(SQLDialect.POSTGRES)
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = error("no database in this test")
            }
        val queue =
            object : WorkQueue {
                override fun enqueue(entry: QueueEntry): Long = error("no queue in this test")

                override fun claimNext(): ClaimedItem? = null

                override fun payloadOf(item: ClaimedItem): String = error("not used")

                override fun complete(item: ClaimedItem) = Unit

                override fun release(item: ClaimedItem) = Unit
            }
        val config = ReplayConfiguration()
        val source = config.replayCandidateSource(dsl, runner)
        val requeue = config.replayRequeue(dsl, runner, JooqInFlightStore(dsl), queue)
        source.shouldBeInstanceOf<JooqReplayCandidateSource>()
        requeue.shouldBeInstanceOf<JooqReplayRequeue>()

        val replica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }
        val bean = config.replayBySelection(replica, source, requeue)
        val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
        // No grant: the replica refuses before the adapters are touched (they would fail on the missing database).
        bean
            .handle(
                ReplayBySelectionRequest(
                    tenant,
                    Principal("nobody", PrincipalKind.HUMAN),
                    ReplayScope("e1", null, null, null),
                    10,
                ),
                Deadline.after(Duration.ofSeconds(5), clock),
            ).shouldBeInstanceOf<ReplayBySelectionResult.Forbidden>()
    }

    @Test
    fun `INV-AC-20606 the ReplaySingleVersion bean is built over the lookup and requeue beans`() {
        val dsl = DSL.using(SQLDialect.POSTGRES)
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = error("no database in this test")
            }
        val queue =
            object : WorkQueue {
                override fun enqueue(entry: QueueEntry): Long = error("no queue in this test")

                override fun claimNext(): ClaimedItem? = null

                override fun payloadOf(item: ClaimedItem): String = error("not used")

                override fun complete(item: ClaimedItem) = Unit

                override fun release(item: ClaimedItem) = Unit
            }
        val config = ReplayConfiguration()
        val lookup = config.replayVersionLookup(dsl, runner)
        val requeue = config.replayRequeue(dsl, runner, JooqInFlightStore(dsl), queue)
        lookup.shouldBeInstanceOf<JooqReplayVersionLookup>()
        val replica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }
        val bean = config.replaySingleVersion(replica, lookup) { _, _, _ -> error("requeue is not reached") }
        bean.shouldBeInstanceOf<ReplaySingleVersion>()
        requeue.shouldBeInstanceOf<JooqReplayRequeue>()
        // The lookup is the first step: a real lookup bean over the missing database fails, so it is the one called.
        runCatching {
            bean.handle(
                ReplaySingleVersionRequest(
                    TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a")),
                    Principal("nobody", PrincipalKind.HUMAN),
                    "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    1,
                ),
                Deadline.after(Duration.ofSeconds(5), clock),
            )
        }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `INV-AC-20588 the service context holds the bean and the controller over it`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBean(ReplayBySelection::class.java)
            context.getBean(TransactionsController::class.java)
        }
    }

    @Test
    fun `INV-AC-20588 the permission string is the ReplayActions constant and no role name is in the wiring`() {
        ReplayActions.REPLAY shouldBe "submission.replay"
        val roles = roleMap.rolesFor(ReplayActions.REPLAY).keys
        (roles.isNotEmpty()) shouldBe true
        val files =
            listOf(
                "src/main/kotlin/com/dynokore/gst/submission/bootstrap/ReplayConfiguration.kt",
                "../submission-adapters/src/main/kotlin/com/dynokore/gst/submission/adapters/http" +
                    "/TransactionsController.kt",
            )
        files.forEach { file ->
            val text = Files.readString(Path.of(file))
            text.contains("\"${ReplayActions.REPLAY}\"") shouldBe false
            roles.none { text.contains(it) } shouldBe true
        }
    }
}
