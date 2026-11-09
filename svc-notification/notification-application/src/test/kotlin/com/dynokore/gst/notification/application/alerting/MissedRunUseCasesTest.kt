package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.JobExpectation
import com.dynokore.gst.notification.domain.alerting.JobKey
import com.dynokore.gst.notification.domain.alerting.MissedRunAlertDraft
import com.dynokore.gst.notification.domain.alerting.MissedRunWithheld
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MissedRunUseCasesTest {
    private class Inbox : ProcessedEvents {
        private val seen = HashSet<String>()

        override fun firstTime(eventId: String) = seen.add(eventId)
    }

    private class Store : JobExpectationStore {
        val map = LinkedHashMap<JobKey, JobExpectation>()

        override fun get(key: JobKey) = map[key]

        override fun put(expectation: JobExpectation) {
            map[expectation.key] = expectation
        }

        override fun all() = map.values.toList()
    }

    private class Sink : PlatformAlertSink {
        val drafts = ArrayList<MissedRunAlertDraft>()

        override fun raise(draft: MissedRunAlertDraft) {
            drafts += draft
        }
    }

    private class Refs(
        val sink: Sink,
        var enabled: Boolean = false,
    ) : AlertedRefs {
        override fun all(): Set<String> = if (enabled) sink.drafts.map { it.alertRef }.toSet() else emptySet()
    }

    private class Fixture(
        recipients: List<String> = listOf("ops@example.test"),
    ) {
        val inbox = Inbox()
        val store = Store()
        val sink = Sink()
        val refs = Refs(sink)
        val registered = HandleJobRegistered(inbox, store)
        val recorded = HandleJobRunRecorded(inbox, store)
        val sweep =
            SweepMissedRuns(
                store,
                object : DefaultRecipients {
                    override fun forRole(role: String) = if (role == "deployment.operator") recipients else emptyList()
                },
                refs,
                sink,
            )
    }

    private fun registration(
        scope: String = "PLATFORM",
        nextRunAt: String? = "2026-11-02T02:00:00Z",
    ): Map<String, Any?> =
        buildMap {
            put("job", "nightly")
            put("ownerService", "svc-a")
            put("entityScope", scope)
            put("tolerance", "PT2H")
            if (nextRunAt != null) put("nextRunAt", nextRunAt)
        }

    private fun run(
        scheduledFor: String? = "2026-11-02T02:00:00Z",
        nextRunAt: String? = "2026-11-03T02:00:00Z",
        entity: String? = null,
    ): Map<String, Any?> =
        buildMap {
            put("job", "nightly")
            put("ownerService", "svc-a")
            if (scheduledFor != null) put("scheduledFor", scheduledFor)
            if (nextRunAt != null) put("nextRunAt", nextRunAt)
            if (entity != null) put("entityScope", entity)
        }

    private val late = Instant.parse("2026-11-02T04:00:00.000000001Z")
    private val nextLate = Instant.parse("2026-11-03T04:00:00.000000001Z")

    @Test
    fun `INV-AC-15939 registration then sweep after deadline raises one alert and the second sweep none`() {
        val f = Fixture()
        f.registered.handle("e1", registration())
        val first = f.sweep.run(late)
        first.raised shouldBe 1
        f.sink.drafts shouldHaveSize 1
        f.sink.drafts[0].alertRef shouldEndWith "2026-11-02T02:00:00Z"
        f.refs.enabled = true
        val second = f.sweep.run(late)
        second.raised shouldBe 0
        second.withheld shouldBe mapOf(MissedRunWithheld.ALREADY_ALERTED to 1)
        f.sink.drafts shouldHaveSize 1
    }

    @Test
    fun `INV-AC-15939 a run recorded before the deadline means no alert`() {
        val f = Fixture()
        f.registered.handle("e1", registration())
        f.recorded.handle("e2", run())
        f.sweep.run(late).raised shouldBe 0
        f.sink.drafts.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-15939 a repeated event id changes nothing`() {
        val f = Fixture()
        f.registered.handle("e1", registration())
        f.recorded.handle("e2", run())
        val before = f.store.all()
        val moved = "2026-12-01T00:00:00Z"
        f.registered.handle("e1", registration(nextRunAt = moved)) shouldBe HandleJobRegisteredResult.Ignored
        f.recorded.handle("e2", run(nextRunAt = moved)) shouldBe HandleJobRunRecordedResult.Ignored
        f.store.all() shouldBe before
    }

    @Test
    fun `INV-AC-15939 a registration without nextRunAt is a contract violation and stores nothing`() {
        val f = Fixture()
        assertThrows(ContractViolation::class.java) { f.registered.handle("e1", registration(nextRunAt = null)) }
        f.store.all().shouldBeEmpty()
        assertThrows(ContractViolation::class.java) { f.registered.handle("e2", registration(nextRunAt = "soon")) }
        f.store.all().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-15939 a run of an ENTITY-scoped job for any entity satisfies the platform expectation`() {
        for (entity in listOf("ent-1", "ent-2")) {
            val f = Fixture()
            f.registered.handle("e1", registration(scope = "ENTITY"))
            f.recorded.handle("e2", run(entity = entity))
            f.sweep.run(late).raised shouldBe 0
            f.sweep.run(nextLate).raised shouldBe 1
        }
    }

    @Test
    fun `INV-AC-15939 a run without nextRunAt or scheduledFor is refused and the expectation is unchanged`() {
        for (bad in listOf(run(nextRunAt = null), run(scheduledFor = null))) {
            val f = Fixture()
            f.registered.handle("e1", registration())
            val before = f.store.all()
            assertThrows(ContractViolation::class.java) { f.recorded.handle("e2", bad) }
            f.store.all() shouldBe before
            f.sweep.run(late).raised shouldBe 1
        }
    }

    @Test
    fun `INV-AC-15939 a run before any registration awaits registration and stores nothing`() {
        val f = Fixture()
        f.recorded.handle("e1", run()) shouldBe HandleJobRunRecordedResult.AwaitingRegistration
        f.store.all().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-15939 no recipients is reported as NO_RECIPIENT and raises nothing`() {
        val f = Fixture(recipients = emptyList())
        f.registered.handle("e1", registration())
        val result = f.sweep.run(late)
        result.raised shouldBe 0
        result.withheld shouldBe mapOf(MissedRunWithheld.NO_RECIPIENT to 1)
        f.sink.drafts.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-15939 tolerance of a registration is read as an ISO duration`() {
        val f = Fixture()
        val result = f.registered.handle("e1", registration()) as HandleJobRegisteredResult.Registered
        result.expectation.tolerance shouldBe Duration.ofHours(2)
    }
}
