package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationTask
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.accreditation.TaskReason
import com.dynokore.gst.controlPlane.domain.accreditation.TaskStatus
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The accreditation step of the release pipeline and the task operations, over in-memory ports (INV-REQ-1365). */
class OpenAccreditationTaskTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-10-19")
    private val request = RequestContext("corr", "cause")
    private val train = ReleaseTarget(ReleaseTarget.Kind.TRAIN, "2026.10.1")

    private val store = LinkedHashMap<String, AccreditationTask>()
    private val checks = LinkedHashMap<ReleaseTarget, AccreditationCheck>()
    private val raised = ArrayList<AccreditationAlert>()
    private val failures = ArrayList<Exception>()
    private val exhausted = ArrayList<ReleaseTarget>()
    private val policy =
        StepRetryPolicy(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(8), 3, 10)
    private val queue = MemoryStepQueue(policy)
    private var latest: AccreditationRecord? = null
    private var alertFails = false
    private var counter = 0

    private val tasks =
        object : AccreditationTaskRepository {
            override fun find(taskRef: String) = store[taskRef]

            override fun findFor(
                releaseRef: String,
                reason: TaskReason,
            ) = store.values.firstOrNull { it.releaseRef == releaseRef && it.reason == reason }

            override fun insert(task: AccreditationTask): Boolean {
                if (findFor(task.releaseRef.orEmpty(), task.reason) != null) return false
                store[task.taskRef] = task
                return true
            }

            override fun page(
                status: TaskStatus?,
                after: TaskCursorKey?,
                limit: Int,
            ) = store.values
                .filter { status == null || it.status == status }
                .sortedWith(compareByDescending<AccreditationTask> { it.openedAt }.thenBy { it.taskRef })
                .filter {
                    after == null || it.openedAt < after.openedAt ||
                        (it.openedAt == after.openedAt && it.taskRef > after.taskRef)
                }.take(limit)

            override fun conclude(
                taskRef: String,
                at: Instant,
                conclusion: String,
            ) = store[taskRef]?.conclude(at, conclusion)?.also { store[taskRef] = it }
        }

    private val links =
        object : ReleaseAccreditationLinks {
            override fun current(target: ReleaseTarget) = checks[target]

            override fun link(
                target: ReleaseTarget,
                check: AccreditationCheck,
            ) {
                checks[target] = check
            }
        }

    private val records =
        object : AccreditationRecords {
            override fun latest() = latest

            override fun find(
                imdaReference: String,
                currentUntil: LocalDate,
            ) = null

            override fun save(record: AccreditationRecord) = Unit
        }

    /** A transaction that restores the stores when the block throws, as a rollback does. */
    private val transactions =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val savedTasks = LinkedHashMap(store)
                val savedChecks = LinkedHashMap(checks)
                val savedRaised = ArrayList(raised)
                val savedRows = LinkedHashMap(queue.rows)
                return try {
                    block()
                } catch (e: Exception) {
                    store.clear()
                    store.putAll(savedTasks)
                    checks.clear()
                    checks.putAll(savedChecks)
                    raised.clear()
                    raised.addAll(savedRaised)
                    queue.rows.clear()
                    queue.rows.putAll(savedRows)
                    throw e
                }
            }
        }

    private val step =
        OpenAccreditationTask(
            AccreditationStepPorts(
                tasks,
                links,
                { alert, _ ->
                    if (alertFails) error("outbox down")
                    raised += alert
                },
                records,
                queue,
            ),
            transactions,
            clock,
            object : StepReporting {
                override fun failed(
                    target: ReleaseTarget,
                    failure: Exception,
                ) {
                    failures += failure
                }

                override fun bookkeepingFailed(
                    target: ReleaseTarget,
                    failure: Exception,
                ) {
                    failures += failure
                }

                override fun exhausted(
                    target: ReleaseTarget,
                    error: String,
                ) {
                    exhausted += target
                }
            },
            { "$it-${++counter}" },
        )

    private fun record(currentUntil: LocalDate) {
        latest =
            AccreditationRecord(
                today.minusDays(300),
                currentUntil,
                "IMDA-1",
                listOf(SubmissionType.TYPE_1A),
                emptyList(),
                Instant.parse("2026-01-01T00:00:00Z"),
                ActorId("alice"),
            )
    }

    private fun released(
        target: ReleaseTarget = train,
        flag: Boolean,
    ) {
        checks[target] = AccreditationCheck(flag, null, null) // what the release transaction stored
    }

    @Test
    fun `INV-AC-2340 a requested assessment opens one OPEN task and one alert and links both to the release`() {
        record(today.plusDays(100))
        released(flag = true)
        val check = step.afterRelease(train, true, request)
        check shouldBe AccreditationCheck(true, "ALERT-2", "TASK-1")
        store.values.single().let {
            it.status shouldBe TaskStatus.OPEN
            it.reason shouldBe TaskReason.REASSESSMENT_NEEDED
            it.releaseRef shouldBe "2026.10.1"
        }
        raised.single().let {
            it.alertRef shouldBe "ALERT-2"
            it.taskRef shouldBe "TASK-1"
            it.currentUntil shouldBe today.plusDays(100)
        }
        checks[train] shouldBe check
    }

    @Test
    fun `a lapsed accreditation opens the lapse task although the request says no assessment`() {
        record(today.minusDays(1))
        released(flag = false)
        step.afterRelease(train, false, request)
        store.values.single().reason shouldBe TaskReason.ACCREDITATION_LAPSED
        // the field is the request's declaration, not whether a task was opened (INV-Q-15454 A)
        checks[train]?.assessmentNeeded shouldBe false
        checks[train]?.taskRef shouldBe "TASK-1"
        checks[train]?.alertRef shouldBe "ALERT-2"
    }

    @Test
    fun `lapsed and requested together open one task with the lapse reason, not two`() {
        record(today.minusDays(1))
        released(flag = true)
        step.afterRelease(train, true, request)
        store.size shouldBe 1
        raised.size shouldBe 1
        store.values.single().reason shouldBe TaskReason.ACCREDITATION_LAPSED
    }

    @Test
    fun `a current accreditation with no assessment requested opens nothing`() {
        record(today)
        released(flag = false)
        step.afterRelease(train, false, request) shouldBe AccreditationCheck(false, null, null)
        store.isEmpty() shouldBe true
        raised.isEmpty() shouldBe true
    }

    @Test
    fun `NOT_RECORDED with no assessment requested opens nothing`() {
        released(flag = false)
        step.afterRelease(train, false, request)
        store.isEmpty() shouldBe true
        raised.isEmpty() shouldBe true
    }

    @Test
    fun `NOT_RECORDED with an assessment requested opens a REASSESSMENT_NEEDED task`() {
        released(flag = true)
        step.afterRelease(train, true, request)
        store.values.single().reason shouldBe TaskReason.REASSESSMENT_NEEDED
        raised.single().currentUntil shouldBe null
    }

    @Test
    fun `a repeat of the same release opens no second task and raises no second alert`() {
        record(today.plusDays(1))
        released(flag = true)
        val first = step.afterRelease(train, true, request)
        step.afterRelease(train, true, request) shouldBe first
        record(today.minusDays(1)) // the accreditation lapsed between the two calls: still the one task
        step.afterRelease(train, true, request) shouldBe first
        store.size shouldBe 1
        raised.size shouldBe 1
    }

    @Test
    fun `a failing alert write leaves no task, is reported, and throws nothing`() {
        record(today.minusDays(1))
        released(flag = true)
        alertFails = true
        step.afterRelease(train, true, request) shouldBe null
        failures.size shouldBe 1
        store.isEmpty() shouldBe true
        checks[train] shouldBe AccreditationCheck(true, null, null)
        alertFails = false
        step.afterRelease(train, true, request)?.taskRef shouldBe "TASK-3" // the retry of the request completes it
        store.size shouldBe 1
    }

    @Test
    fun `a release record that is gone is a reported failure of the step only`() {
        record(today.minusDays(1))
        step.afterRelease(train, true, request) shouldBe null
        store.isEmpty() shouldBe true
    }

    @Test
    fun `a pack version gets its own task keyed by its pack reference`() {
        val pack = ReleaseTarget(ReleaseTarget.Kind.PACK, "PACK-1")
        released(pack, true)
        step.afterRelease(pack, true, request)
        store.values.single().releaseRef shouldBe "PACK-1"
        checks[pack]?.taskRef shouldBe "TASK-1"
    }

    // --- the task operations
    private val permissions = mutableSetOf<Pair<String, String>>()
    private val grants = OperatorPermissions { actor, key -> (actor to key) in permissions }
    private val conclude = ConcludeAccreditationTask(tasks, grants, clock)
    private val queries = AccreditationTaskQueries(tasks, grants, transactions)

    private fun open(ref: String = "TASK-X") =
        AccreditationTask(ref, TaskStatus.OPEN, TaskReason.REASSESSMENT_NEEDED, clock.instant(), "ALERT-X", "R-$ref")
            .also { store[ref] = it }

    private fun command(
        conclusion: String,
        actor: String = "rita",
        ref: String = "TASK-X",
    ) = ConcludeAccreditationTaskCommand(ActorId(actor), ref, conclusion, request)

    @Test
    fun `conclude on an OPEN task sets the three fields, with the clock instant`() {
        open()
        val done = conclude.perform(command("reviewed"), store["TASK-X"])
        done.status shouldBe TaskStatus.CONCLUDED
        done.concludedAt shouldBe clock.instant()
        done.conclusion shouldBe "reviewed"
    }

    @Test
    fun `conclude is permitted only for the conclude key and the caller themself`() {
        permissions += "rita" to AccreditationPermission.CONCLUDE
        conclude.permits("rita", command("x")) shouldBe true
        conclude.permits("rita", command("x", actor = "bob")) shouldBe false
        conclude.permits("bob", command("x", actor = "bob")) shouldBe false
        permissions += "carl" to AccreditationPermission.RECORD
        conclude.permits("carl", command("x", actor = "carl")) shouldBe false
        permissions += "dora" to "accreditation.conclude "
        conclude.permits("dora", command("x", actor = "dora")) shouldBe false
    }

    @Test
    fun `a repeat with the same conclusion reports the task and another conclusion is refused`() {
        open()
        val done = conclude.perform(command("reviewed"), store["TASK-X"])
        conclude.priorResult(command("reviewed"), done) shouldBe done
        conclude.priorResult(command("other"), done) shouldBe null
        conclude.unmetPrecondition(command("other"), done)!!.startsWith("TASK_ALREADY_CONCLUDED") shouldBe true
        conclude.unmetPrecondition(command("x", ref = "NONE"), null)!!.startsWith("TASK_NOT_FOUND") shouldBe true
    }

    @Test
    fun `the list pages 120 tasks with limit 50 in three pages and returns each task once`() {
        repeat(120) {
            val ref = "T-" + it.toString().padStart(3, '0')
            // equal openedAt for pairs, so the task_ref tie-break is exercised
            store[ref] =
                AccreditationTask(
                    ref,
                    TaskStatus.OPEN,
                    TaskReason.REASSESSMENT_NEEDED,
                    clock.instant().plusSeconds(
                        (
                            it /
                                2
                        ).toLong(),
                    ),
                    "A-$ref",
                    "R-$ref",
                )
        }
        permissions += "rita" to AccreditationPermission.TASK_LIST
        val seen = ArrayList<String>()
        var after: TaskCursorKey? = null
        val sizes = ArrayList<Int>()
        do {
            val page = (queries.list("rita", null, after, 50) as TaskRead.Found).value
            sizes += page.items.size
            seen += page.items.map { it.taskRef }
            after = page.items.lastOrNull()?.let { TaskCursorKey(it.openedAt, it.taskRef) }
        } while (page.more)
        sizes shouldBe listOf(50, 50, 20)
        seen.toSet().size shouldBe 120
        seen.size shouldBe 120
        seen.first() shouldBe "T-118" // newest first, then task_ref ascending
    }

    @Test
    fun `a reader without the read key is denied and a limit outside 1 to 500 is refused`() {
        queries.list("nobody", null, null, 10) shouldBe TaskRead.Denied
        queries.get("nobody", "TASK-X") shouldBe TaskRead.Denied
        listOf(0, 501, -1).forEach { limit ->
            runCatching { queries.list("nobody", null, null, limit) }.isFailure shouldBe true
        }
    }

    @Test
    fun `list needs accreditation-task list, get needs accreditation-task view, the record key opens neither`() {
        store["TASK-X"] =
            AccreditationTask("TASK-X", TaskStatus.OPEN, TaskReason.REASSESSMENT_NEEDED, clock.instant(), "A", "R")
        permissions += "carl" to AccreditationPermission.RECORD
        queries.list("carl", null, null, 10) shouldBe TaskRead.Denied
        queries.get("carl", "TASK-X") shouldBe TaskRead.Denied
        permissions += "lena" to "accreditation-task.list"
        queries.list("lena", null, null, 10).shouldBeInstanceOf<TaskRead.Found<*>>()
        queries.get("lena", "TASK-X") shouldBe TaskRead.Denied
        permissions += "vic" to "accreditation-task.view"
        queries.get("vic", "TASK-X").shouldBeInstanceOf<TaskRead.Found<*>>()
        queries.list("vic", null, null, 10) shouldBe TaskRead.Denied
    }

    private fun pending(
        target: ReleaseTarget = train,
        flag: Boolean = true,
    ) = queue.insert(MemoryStepQueue.step(target, flag, clock.instant().minusSeconds(120)))

    @Test
    fun `a step forced to fail keeps the row with one attempt and no task, then one relay run opens the task`() {
        record(today.plusDays(100))
        released(flag = true)
        pending()
        alertFails = true
        step.afterRelease(train, true, request) shouldBe null
        store.isEmpty() shouldBe true
        raised.isEmpty() shouldBe true
        queue.rows.getValue(train).let {
            it.status shouldBe "PENDING"
            it.attempts shouldBe 1
            it.lastError shouldBe "IllegalStateException"
            it.nextAttemptAt shouldBe clock.instant().plus(policy.backoff(1))
        }
        alertFails = false
        val later = Clock.fixed(clock.instant().plus(policy.backoff(1)), ZoneOffset.UTC)
        relay(later)
        store.size shouldBe 1
        raised.size shouldBe 1
        checks[train]?.taskRef shouldBe store.values.single().taskRef
        queue.rows.isEmpty() shouldBe true
    }

    private fun relay(at: Clock) {
        val relayStep =
            OpenAccreditationTask(
                AccreditationStepPorts(tasks, links, { alert, _ -> raised += alert }, records, queue),
                transactions,
                at,
                object : StepReporting {
                    override fun failed(target: ReleaseTarget, failure: Exception) = Unit
                    override fun bookkeepingFailed(target: ReleaseTarget, failure: Exception) = Unit
                    override fun exhausted(target: ReleaseTarget, error: String) = Unit
                },
                { "$it-${++counter}" },
            )
        queue.due(at.instant(), policy.batchSize).forEach(relayStep::retry)
    }

    @Test
    fun `no inline attempt and one relay run opens the task, a second run opens nothing more`() {
        released(flag = true)
        pending()
        val due = Clock.fixed(clock.instant(), ZoneOffset.UTC)
        relay(due)
        relay(due)
        store.size shouldBe 1
        raised.size shouldBe 1
        queue.rows.isEmpty() shouldBe true
    }

    @Test
    fun `a relay run that overlaps a claim held elsewhere skips the row and opens nothing`() {
        released(flag = true)
        pending()
        queue.claimed = setOf(train)
        relay(Clock.fixed(clock.instant(), ZoneOffset.UTC))
        store.isEmpty() shouldBe true
        queue.rows.containsKey(train) shouldBe true
    }

    @Test
    fun `a release with no trigger leaves no row after the step`() {
        record(today.plusDays(100))
        released(flag = false)
        pending(flag = false)
        step.afterRelease(train, false, request)
        queue.rows.isEmpty() shouldBe true
    }

    @Test
    fun `a TRAIN row and a PACK row with the same reference do not collide`() {
        val pack = ReleaseTarget(ReleaseTarget.Kind.PACK, train.ref)
        pending(train)
        pending(pack)
        queue.rows.size shouldBe 2
    }

    @Test
    fun `at the maximum attempts the row is EXHAUSTED, reported once, kept, and no longer due`() {
        released(flag = true)
        pending()
        record(today.plusDays(100))
        alertFails = true
        var at = clock.instant()
        repeat(policy.maxAttempts) {
            val tick = Clock.fixed(at, ZoneOffset.UTC)
            val failing =
                OpenAccreditationTask(
                    AccreditationStepPorts(tasks, links, { _, _ -> error("down") }, records, queue),
                    transactions,
                    tick,
                    object : StepReporting {
                        override fun failed(target: ReleaseTarget, failure: Exception) = Unit
                        override fun bookkeepingFailed(target: ReleaseTarget, failure: Exception) = Unit
                        override fun exhausted(target: ReleaseTarget, error: String) {
                            exhausted += target
                        }
                    },
                )
            failing.retry(train)
            at = at.plus(policy.backoffCap)
        }
        queue.rows.getValue(train).let {
            it.status shouldBe "EXHAUSTED"
            it.attempts shouldBe policy.maxAttempts
        }
        exhausted shouldBe listOf(train)
        queue.due(at.plusSeconds(1_000_000), policy.batchSize).isEmpty() shouldBe true
    }

    @Test
    fun `the backoff doubles per failure and stops at the cap`() {
        policy.backoff(1) shouldBe Duration.ofMinutes(2)
        policy.backoff(2) shouldBe Duration.ofMinutes(4)
        policy.backoff(3) shouldBe Duration.ofMinutes(8)
        policy.backoff(9) shouldBe Duration.ofMinutes(8)
    }
}
