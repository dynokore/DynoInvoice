package com.dynokore.gst.consoleBff.application.deferral

import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralKind
import com.dynokore.gst.consoleBff.domain.deferral.DeferralState
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Contract of [DeferralStore]; an adapter test extends this class and supplies its store. */
abstract class DeferralStoreContractTest {
    protected abstract fun newStore(): DeferralStore

    /** Number of rows (any state) the store holds for the key; the adapter supplies its own count. */
    protected abstract fun rowCount(store: DeferralStore): Int

    private val at = Instant.parse("2026-11-14T00:00:00Z")

    private fun candidate(
        entityRef: String = "e1",
        kind: DeferralKind = DeferralKind.DOCUMENT,
        ref: String = "d1",
        by: String = "u1",
    ) = Deferral(UUID.randomUUID(), entityRef, DeferralTarget(kind, ref), DeferralState.DEFERRED, by, at)

    @Test
    fun `INV-AC-21154 defer on an empty store returns the candidate unchanged`() {
        val store = newStore()
        val c = candidate()
        store.deferIfAbsent(c) shouldBe c
        store.findDeferred("e1", c.target) shouldBe c
    }

    @Test
    fun `INV-AC-21154 repeat for the same key returns the first row unchanged and holds one row`() {
        val store = newStore()
        val first = store.deferIfAbsent(candidate(by = "u1"))
        val second = store.deferIfAbsent(candidate(by = "u2").copy(deferredAt = at.plusSeconds(60)))
        second shouldBe first
        second.deferredBy shouldBe "u1"
        rowCount(store) shouldBe 1
    }

    @Test
    fun `INV-AC-21154 the same ref under the other kind is a different key`() {
        val store = newStore()
        val doc = store.deferIfAbsent(candidate(kind = DeferralKind.DOCUMENT))
        val group = store.deferIfAbsent(candidate(kind = DeferralKind.GROUP))
        (group.deferralId == doc.deferralId) shouldBe false
        rowCount(store) shouldBe 2
    }

    @Test
    fun `INV-AC-21154 the same target under another entityRef is a different key`() {
        val store = newStore()
        val one = store.deferIfAbsent(candidate(entityRef = "e1"))
        val two = store.deferIfAbsent(candidate(entityRef = "e2"))
        (two.deferralId == one.deferralId) shouldBe false
        rowCount(store) shouldBe 2
    }

    @Test
    fun `INV-AC-21154 undo returns the row as UNDONE with the same id deferredBy and deferredAt`() {
        val store = newStore()
        val c = store.deferIfAbsent(candidate())
        store.undo("e1", c.deferralId) shouldBe c.copy(state = DeferralState.UNDONE)
        store.findDeferred("e1", c.target) shouldBe null
    }

    @Test
    fun `INV-AC-21154 a second undo of the same deferralId returns null`() {
        val store = newStore()
        val c = store.deferIfAbsent(candidate())
        store.undo("e1", c.deferralId)
        store.undo("e1", c.deferralId) shouldBe null
    }

    @Test
    fun `INV-AC-21154 undo of an unknown deferralId returns null`() {
        newStore().undo("e1", UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `INV-AC-21154 undo with the deferralId of another entityRef returns null and leaves the row DEFERRED`() {
        val store = newStore()
        val c = store.deferIfAbsent(candidate(entityRef = "e1"))
        store.undo("e2", c.deferralId) shouldBe null
        store.findDeferred("e1", c.target) shouldBe c
    }

    @Test
    fun `INV-AC-21154 defer after undo stores a new row with a new deferralId in state DEFERRED`() {
        val store = newStore()
        val old = store.deferIfAbsent(candidate())
        store.undo("e1", old.deferralId)
        val again = store.deferIfAbsent(candidate())
        again.state shouldBe DeferralState.DEFERRED
        (again.deferralId == old.deferralId) shouldBe false
        store.findDeferred("e1", old.target) shouldBe again
    }
}

/** In-memory store, used only by tests. */
private class InMemoryDeferralStore : DeferralStore {
    val rows = mutableListOf<Deferral>()

    override fun deferIfAbsent(candidate: Deferral): Deferral =
        findDeferred(candidate.entityRef, candidate.target)
            ?: candidate.copy(state = DeferralState.DEFERRED).also { rows += it }

    override fun undo(
        entityRef: String,
        deferralId: UUID,
    ): Deferral? {
        val i =
            rows.indexOfFirst {
                it.entityRef == entityRef && it.deferralId == deferralId &&
                    it.state == DeferralState.DEFERRED
            }
        if (i < 0) return null
        return rows[i].copy(state = DeferralState.UNDONE).also { rows[i] = it }
    }

    override fun findDeferred(
        entityRef: String,
        target: DeferralTarget,
    ): Deferral? =
        rows.firstOrNull {
            it.entityRef == entityRef && it.target == target &&
                it.state == DeferralState.DEFERRED
        }
}

class InMemoryDeferralStoreContractTest : DeferralStoreContractTest() {
    override fun newStore(): DeferralStore = InMemoryDeferralStore()

    override fun rowCount(store: DeferralStore): Int =
        (store as InMemoryDeferralStore).rows.count {
            it.state ==
                DeferralState.DEFERRED
        }
}
