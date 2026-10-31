package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-REQ-1199, INV-AC-9089: [StructureReplica.isSynced] follows the snapshot marker and the disconnect. */
class StructureReplicaSyncTest {
    private val replica = StructureReplica(MutableClock())

    @Test
    fun `is not synced before the snapshot, synced after it and not synced after a disconnect`() {
        replica.isSynced() shouldBe false
        replica.observeHead(3)
        replica.isSynced() shouldBe false
        replica.onSnapshotComplete(3)
        replica.isSynced() shouldBe true
        replica.onDisconnected()
        replica.isSynced() shouldBe false
        replica.onSnapshotComplete(3)
        replica.isSynced() shouldBe true
    }

    @Test
    fun `a replica with no lag bound keeps its Unknown-free lag behaviour whatever isSynced says`() {
        val before = replica.lag()
        replica.onDisconnected()
        replica.lag() shouldBe before
    }
}
