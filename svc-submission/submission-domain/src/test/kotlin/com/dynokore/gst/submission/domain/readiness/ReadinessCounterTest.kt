package com.dynokore.gst.submission.domain.readiness

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.AWAITING_PRECEDING
import com.dynokore.gst.submission.domain.ordering.LifecycleState.BLOCKED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.HELD
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.readiness.ReadinessResult.Blocked
import com.dynokore.gst.submission.domain.readiness.ReadinessResult.Ready
import com.dynokore.gst.submission.domain.readiness.ReadinessResult.UnableToDetermine
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.lang.reflect.Modifier
import java.time.LocalDate

class ReadinessCounterTest {
    private val day = LocalDate.of(2026, 10, 15)
    private var seq = 0

    private fun entry(
        vararg versions: LifecycleState,
        date: LocalDate = day,
        overridden: Boolean = false,
        code: String? = null,
        id: String = "doc-" + (seq++).toString().padStart(4, '0'),
    ) = ReadinessEntry(id, versions.toList(), date, overridden, code)

    private fun run(
        entries: List<ReadinessEntry>,
        start: LocalDate? = null,
        data: Boolean = true,
    ) = ReadinessCounter.evaluate(entries, start, data)

    private fun counts(r: ReadinessResult) =
        when (r) {
            is Ready -> r.counts
            is Blocked -> r.counts
            UnableToDetermine -> error("no counts")
        }

    private fun residual(r: ReadinessResult) =
        when (r) {
            is Ready -> r.residual
            is Blocked -> r.residual
            UnableToDetermine -> error("no residual")
        }

    @Test
    fun `INV-AC-143 INV-AC-13083 twelve acknowledged entries are ready`() {
        val r = run(List(12) { entry(ACKNOWLEDGED) })
        r.shouldBeInstanceOf<Ready>()
        counts(r) shouldBe Counts(12, 12, 0, 0, 0, 0, 0)
        residual(r) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-144 INV-AC-13081 INV-AC-611 a dead-lettered entry is unsubmitted and blocks`() {
        val dead = entry(DEAD_LETTERED, id = "dead")
        val r = run(List(11) { entry(ACKNOWLEDGED) } + dead)
        r.shouldBeInstanceOf<Blocked>().count shouldBe 1
        counts(r).blockedOrError shouldBe 1
        counts(r).unsubmitted shouldBe 1
        residual(r) shouldBe listOf(Residual("dead", Bucket.BLOCKED_OR_ERROR, DEAD_LETTERED, null, false))
    }

    @Test
    fun `INV-AC-100 a held entry lists its reason code`() {
        val r = run(listOf(entry(HELD, code = "MDM-001", id = "h")))
        r.shouldBeInstanceOf<Blocked>().count shouldBe 1
        residual(r).single().reasonCode shouldBe "MDM-001"
    }

    @Test
    fun `INV-AC-101 a blocked entry lists its reason code`() {
        val r = run(listOf(entry(BLOCKED, code = "PAY-001", id = "b")))
        r.shouldBeInstanceOf<Blocked>().count shouldBe 1
        residual(r).single().reasonCode shouldBe "PAY-001"
    }

    @Test
    fun `INV-AC-356 INV-AC-13080 only documents from the start date count and NOT_SUBMITTABLE never`() {
        val before = day.minusDays(1)
        val r =
            run(
                listOf(
                    entry(QUEUED, date = before),
                    entry(HELD, date = before),
                    entry(QUEUED, date = day),
                    entry(QUEUED, date = day.plusDays(1)),
                    entry(NOT_SUBMITTABLE, date = day),
                ),
                start = day,
            )
        r.shouldBeInstanceOf<Blocked>().count shouldBe 2
        counts(r).inScope shouldBe 2
        counts(r).unsubmitted shouldBe 2
    }

    @Test
    fun `INV-AC-356 a null start date filters nothing`() {
        counts(run(listOf(entry(QUEUED, date = LocalDate.MIN)), start = null)).inScope shouldBe 1
    }

    @Test
    fun `INV-AC-1964 INV-AC-13083 three blocked entries give one blocked verdict and no score property`() {
        val r = run(List(3) { entry(BLOCKED, code = "PAY-001") })
        r.shouldBeInstanceOf<Blocked>().count shouldBe 3

        fun names(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }
        names(Ready::class.java) shouldContainExactlyInAnyOrder listOf("counts", "residual")
        names(Blocked::class.java) shouldContainExactlyInAnyOrder listOf("count", "counts", "residual")
        names(UnableToDetermine::class.java) shouldBe emptyList()
        names(Counts::class.java) shouldContainExactlyInAnyOrder
            listOf("inScope", "acknowledged", "queued", "blockedOrError", "closed", "unsubmitted", "outstanding")
    }

    @Test
    fun `INV-AC-1750 INV-AC-13082 an awaiting-preceding entry is queued, unsubmitted and listed`() {
        val r = run(List(11) { entry(ACKNOWLEDGED) } + entry(AWAITING_PRECEDING, id = "cn"))
        counts(r).queued shouldBe 1
        counts(r).blockedOrError shouldBe 0
        counts(r).unsubmitted shouldBe 1
        residual(r) shouldBe listOf(Residual("cn", Bucket.QUEUED, AWAITING_PRECEDING, null, false))
    }

    @Test
    fun `INV-AC-13084 dead-lettered after rejected, acknowledged then rejected, rejected alone`() {
        val r =
            run(
                listOf(
                    entry(REJECTED, DEAD_LETTERED, id = "a"),
                    entry(ACKNOWLEDGED, REJECTED, id = "b"),
                    entry(REJECTED, id = "c"),
                ),
            )
        residual(r).map { it.docUuid to it.bucket } shouldBe
            listOf("a" to Bucket.BLOCKED_OR_ERROR, "c" to Bucket.BLOCKED_OR_ERROR)
        counts(r).acknowledged shouldBe 1
        counts(r).unsubmitted shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleStateTable")
    fun `INV-AC-13083 bucket and unsubmitted flag of each state alone`(
        state: LifecycleState,
        bucket: Bucket?,
        unsubmitted: Boolean,
    ) {
        val r = run(listOf(entry(state, id = "x")))
        if (bucket == null) {
            counts(r).inScope shouldBe 0
        } else {
            counts(r).inScope shouldBe 1
            val c = counts(r)
            when (bucket) {
                Bucket.ACKNOWLEDGED -> c.acknowledged shouldBe 1
                Bucket.QUEUED -> c.queued shouldBe 1
                Bucket.BLOCKED_OR_ERROR -> c.blockedOrError shouldBe 1
                Bucket.CLOSED -> c.closed shouldBe 1
            }
        }
        (counts(r).unsubmitted == 1) shouldBe unsubmitted
        residual(r).map { it.bucket } shouldBe if (unsubmitted) listOf(bucket) else emptyList()
    }

    @Test
    fun `INV-AC-13084 the earlier acknowledged version decides a rejected latest in either order`() {
        counts(run(listOf(entry(ACKNOWLEDGED, REJECTED)))).acknowledged shouldBe 1
        counts(run(listOf(entry(REJECTED, ACKNOWLEDGED)))).acknowledged shouldBe 1
        counts(run(listOf(entry(REJECTED)))).blockedOrError shouldBe 1
        counts(run(listOf(entry(ACKNOWLEDGED, QUEUED)))).queued shouldBe 1
    }

    @Test
    fun `INV-AC-13083 an overridden queued entry is ready but still listed`() {
        val r = run(listOf(entry(QUEUED, overridden = true, id = "o")))
        r.shouldBeInstanceOf<Ready>()
        counts(r).unsubmitted shouldBe 1
        counts(r).outstanding shouldBe 0
        residual(r) shouldBe listOf(Residual("o", Bucket.QUEUED, QUEUED, null, true))
    }

    @Test
    fun `INV-AC-13083 empty list is ready with zero counts when data is available, unable to determine otherwise`() {
        run(emptyList(), data = true) shouldBe Ready(Counts(0, 0, 0, 0, 0, 0, 0), emptyList())
        run(emptyList(), data = false) shouldBe UnableToDetermine
        run(List(3) { entry(BLOCKED) }, data = false) shouldBe UnableToDetermine
    }

    @Test
    fun `INV-AC-1964 two reason codes count as two items, residual is ordered by docUuid`() {
        val r = run(listOf(entry(BLOCKED, code = "PAY-002", id = "b"), entry(BLOCKED, code = "PAY-001", id = "a")))
        r.shouldBeInstanceOf<Blocked>().count shouldBe 2
        residual(r).map { it.docUuid } shouldBe listOf("a", "b")
    }

    @Test
    fun `INV-AC-13083 empty versions, blank id and duplicate id are refused`() {
        assertThrows<IllegalArgumentException> { ReadinessEntry("d", emptyList(), day, false) }
        assertThrows<IllegalArgumentException> { ReadinessEntry(" ", listOf(QUEUED), day, false) }
        assertThrows<IllegalArgumentException> { run(listOf(entry(QUEUED, id = "d"), entry(HELD, id = "d"))) }
        assertThrows<IllegalArgumentException> {
            run(
                listOf(entry(QUEUED, id = "d"), entry(HELD, id = "d")),
                data = false,
            )
        }
    }

    @Test
    fun `INV-AC-13083 equal input gives equal output`() {
        val es = listOf(entry(QUEUED, id = "q"), entry(HELD, id = "h"))
        run(es) shouldBe run(es.toList())
    }

    @Test
    fun `INV-AC-13083 LifecycleState equals the union of the schema enums`() {
        val text = File(System.getProperty("gst.contract.dir"), "schemas/lifecycle-states.schema.json").readText()

        fun enumOf(def: String): List<String> {
            val block = Regex("\"$def\"\\s*:\\s*\\{[^\\[]*\"enum\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            return Regex("\"([A-Z_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        }
        val union = enumOf("documentLevelState") + enumOf("versionLevelState")
        union.size shouldBe 18
        LifecycleState.entries.map { it.name } shouldContainExactlyInAnyOrder union
    }

    companion object {
        /** counts_as_unsubmitted of lifecycle-states.yaml (invoice-docs c99b8e6); REJECTED alone is Yes. */
        @JvmStatic
        fun singleStateTable(): List<Arguments> =
            listOf(
                Arguments.of(LifecycleState.INGESTED, Bucket.QUEUED, true),
                Arguments.of(HELD, Bucket.BLOCKED_OR_ERROR, true),
                Arguments.of(BLOCKED, Bucket.BLOCKED_OR_ERROR, true),
                Arguments.of(NOT_SUBMITTABLE, null, false),
                Arguments.of(LifecycleState.READY, Bucket.QUEUED, true),
                Arguments.of(AWAITING_PRECEDING, Bucket.QUEUED, true),
                Arguments.of(QUEUED, Bucket.QUEUED, true),
                Arguments.of(LifecycleState.SENT, Bucket.QUEUED, true),
                Arguments.of(LifecycleState.SENT_UNCONFIRMED, Bucket.QUEUED, true),
                Arguments.of(LifecycleState.RETRY, Bucket.QUEUED, true),
                Arguments.of(DEAD_LETTERED, Bucket.BLOCKED_OR_ERROR, true),
                Arguments.of(REJECTED, Bucket.BLOCKED_OR_ERROR, true),
                Arguments.of(LifecycleState.CANCELLED, Bucket.CLOSED, false),
                Arguments.of(ACKNOWLEDGED, Bucket.ACKNOWLEDGED, false),
                Arguments.of(LifecycleState.SUPERSEDED, Bucket.ACKNOWLEDGED, false),
                Arguments.of(LifecycleState.NETWORK_ISSUED, Bucket.QUEUED, true),
                Arguments.of(LifecycleState.EXTERNALLY_SUBMITTED, Bucket.ACKNOWLEDGED, false),
                Arguments.of(LifecycleState.WITHDRAWN, Bucket.CLOSED, false),
            )
    }
}
