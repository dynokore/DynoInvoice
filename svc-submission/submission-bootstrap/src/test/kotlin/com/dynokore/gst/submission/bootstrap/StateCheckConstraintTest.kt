package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The `state` CHECK of the queue migration holds exactly the names of `LifecycleState` (INV-AC-2140).
 */
class StateCheckConstraintTest {
    @Test
    fun `INV-AC-2140 migration state check equals the LifecycleState name set`() {
        val sql =
            checkNotNull(
                javaClass.getResource("/db/migration/V202610300900__submission_version_queue.sql"),
            ).readText()
        val line = sql.lines().single { it.trimStart().startsWith("state text not null check") }
        val listed = Regex("""state in \(([^)]*)\)""").find(line)!!.groupValues[1]
        val names = Regex("""'([^']+)'""").findAll(listed).map { it.groupValues[1] }.toList()
        names.size shouldBe names.toSet().size
        names.toSet() shouldBe LifecycleState.entries.map { it.name }.toSet()
    }
}
