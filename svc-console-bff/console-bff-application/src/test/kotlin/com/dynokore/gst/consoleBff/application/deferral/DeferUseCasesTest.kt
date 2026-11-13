package com.dynokore.gst.consoleBff.application.deferral

import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralKind
import com.dynokore.gst.consoleBff.domain.deferral.DeferralState
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DeferUseCasesTest {
    private val now = Instant.parse("2026-11-14T01:02:03Z")
    private val doc = DeferralTarget(DeferralKind.DOCUMENT, "D1")
    private val group = DeferralTarget(DeferralKind.GROUP, "owner-a/PAY-006")

    private class Fixture(
        grant: Boolean = true,
        now: Instant,
    ) {
        val calls = mutableListOf<String>()
        val asked = mutableListOf<List<String>>()
        val rows = mutableListOf<Deferral>()
        private var seq = 0
        val authority =
            DeferAuthority { t, u, e, p ->
                calls += "authority"
                asked += listOf(t, u, e, p)
                grant
            }
        val targets =
            object : DeferralTargets {
                override fun openDocument(
                    entityRef: String,
                    docUuid: String,
                ): Boolean {
                    calls += "targets"
                    return entityRef == "E1" && docUuid == "D1"
                }

                override fun openGroup(
                    entityRef: String,
                    groupKey: String,
                ): Boolean {
                    calls += "targets"
                    return entityRef == "E1" && groupKey == "owner-a/PAY-006"
                }
            }
        val store =
            object : DeferralStore {
                override fun deferIfAbsent(candidate: Deferral): Deferral {
                    calls += "store"
                    return rows.firstOrNull {
                        it.entityRef == candidate.entityRef && it.target == candidate.target &&
                            it.state == DeferralState.DEFERRED
                    } ?: candidate.also { rows += it }
                }

                override fun undo(
                    entityRef: String,
                    deferralId: UUID,
                ): Deferral? {
                    calls += "store"
                    val i = rows.indexOfFirst { it.deferralId == deferralId && it.state == DeferralState.DEFERRED }
                    if (i < 0) return null
                    return rows[i].copy(state = DeferralState.UNDONE).also { rows[i] = it }
                }

                override fun findDeferred(
                    entityRef: String,
                    target: DeferralTarget,
                ): Deferral? {
                    calls += "store"
                    return rows.firstOrNull {
                        it.entityRef == entityRef && it.target == target &&
                            it.state == DeferralState.DEFERRED
                    }
                }
            }
        val defer =
            DeferTarget(authority, targets, store, Clock.fixed(now, ZoneOffset.UTC)) {
                UUID.fromString("00000000-0000-0000-0000-" + (++seq).toString().padStart(12, '0'))
            }
        val undefer = UndeferTarget(authority, store)
    }

    private fun fx(grant: Boolean = true) = Fixture(grant, now)

    @Test
    fun `INV-AC-21160 first defer returns Deferred with DEFERRED, the user and the clock`() {
        val f = fx()
        val r = f.defer.execute("T1", "U1", "E1", doc) as DeferResult.Deferred
        r.deferral.state shouldBe DeferralState.DEFERRED
        r.deferral.deferredBy shouldBe "U1"
        r.deferral.deferredAt shouldBe now
    }

    @Test
    fun `INV-AC-21160 repeat by another user returns the same row and the store holds one row`() {
        val f = fx()
        val a = f.defer.execute("T1", "U1", "E1", doc) as DeferResult.Deferred
        val b = f.defer.execute("T1", "U2", "E1", doc) as DeferResult.Deferred
        b.deferral.deferralId shouldBe a.deferral.deferralId
        b.deferral.deferredBy shouldBe "U1"
        b.deferral.deferredAt shouldBe now
        f.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-21160 repeat is answered from the store before the existence check`() {
        val f = fx()
        val gone = DeferralTarget(DeferralKind.DOCUMENT, "X9")
        val stored = Deferral(UUID.randomUUID(), "E1", gone, DeferralState.DEFERRED, "U1", now)
        f.rows += stored
        f.defer.execute("T1", "U2", "E1", gone) shouldBe DeferResult.Deferred(stored)
        f.calls.contains("targets") shouldBe false
    }

    @Test
    fun `INV-AC-21160 missing document is NotFound and stores nothing`() {
        val f = fx()
        f.defer.execute("T1", "U1", "E1", DeferralTarget(DeferralKind.DOCUMENT, "X9")) shouldBe DeferResult.NotFound
        f.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21160 unknown group is NotFound and a group key is not read as a document`() {
        val f = fx()
        f.defer.execute("T1", "U1", "E1", DeferralTarget(DeferralKind.GROUP, "D1")) shouldBe DeferResult.NotFound
        f.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21160 known group is Deferred`() {
        val f = fx()
        val r = f.defer.execute("T1", "U1", "E1", group) as DeferResult.Deferred
        r.deferral.target shouldBe group
        f.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-21160 group of another owner is NotFound and stores nothing`() {
        val f = fx()
        f.defer.execute("T1", "U1", "E1", DeferralTarget(DeferralKind.GROUP, "owner-b/PAY-006")) shouldBe
            DeferResult.NotFound
        f.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21160 call order asks the authority once with the key before targets and store`() {
        val f = fx()
        f.defer.execute("T1", "U1", "E1", doc)
        f.calls.first() shouldBe "authority"
        f.calls.count { it == "authority" } shouldBe 1
        f.asked shouldBe listOf(listOf("T1", "U1", "E1", "exception.defer"))
        val g = fx()
        g.undefer.execute("T1", "U1", "E1", UUID.randomUUID())
        g.calls.first() shouldBe "authority"
        g.calls.count { it == "authority" } shouldBe 1
        g.asked shouldBe listOf(listOf("T1", "U1", "E1", "exception.defer"))
    }

    @Test
    fun `INV-AC-21160 the authority call carries tenant, user, entity and the key text by equality`() {
        val f = fx()
        f.defer.execute("T1", "U7", "E1", doc)
        f.asked.single()[0] shouldBe "T1"
        f.asked.single()[1] shouldBe "U7"
        f.asked.single()[2] shouldBe "E1"
        f.asked.single()[3] shouldBe "exception.defer"
        val g = fx()
        g.undefer.execute("T9", "U8", "E2", UUID.randomUUID())
        g.asked.single() shouldBe listOf("T9", "U8", "E2", "exception.defer")
    }

    @Test
    fun `INV-AC-21160 denied defer is Forbidden and neither targets nor store were called`() {
        val f = fx(grant = false)
        f.defer.execute("T1", "U1", "E1", doc) shouldBe DeferResult.Forbidden
        f.calls shouldBe listOf("authority")
    }

    @Test
    fun `INV-AC-21160 undo returns Undone with state UNDONE`() {
        val f = fx()
        val a = f.defer.execute("T1", "U1", "E1", doc) as DeferResult.Deferred
        val r = f.undefer.execute("T1", "U2", "E1", a.deferral.deferralId) as UndeferResult.Undone
        r.deferral.state shouldBe DeferralState.UNDONE
        r.deferral.deferralId shouldBe a.deferral.deferralId
    }

    @Test
    fun `INV-AC-21160 repeat undo is NotFound`() {
        val f = fx()
        val a = f.defer.execute("T1", "U1", "E1", doc) as DeferResult.Deferred
        f.undefer.execute("T1", "U1", "E1", a.deferral.deferralId)
        f.undefer.execute("T1", "U1", "E1", a.deferral.deferralId) shouldBe UndeferResult.NotFound
    }

    @Test
    fun `INV-AC-21160 unknown undo id is NotFound`() {
        fx().undefer.execute("T1", "U1", "E1", UUID.randomUUID()) shouldBe UndeferResult.NotFound
    }

    @Test
    fun `INV-AC-21160 denied undo is Forbidden with no store call`() {
        val f = fx(grant = false)
        f.undefer.execute("T1", "U1", "E1", UUID.randomUUID()) shouldBe UndeferResult.Forbidden
        f.calls shouldBe listOf("authority")
    }

    @Test
    fun `INV-AC-21160 constant value equals exception dot defer`() {
        DeferPermissions.EXCEPTION_DEFER shouldBe "exception.defer"
    }
}
