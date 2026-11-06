package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** INV-Q-17470 A: the temporary `validate-group-membership: false` of the unmodified application.yaml fails closed. */
class GroupMembershipGateTest {
    @Test
    fun `INV-AC-14435 membership validation is false under a comment naming the ruling and the removing task`(
        @TempDir mount: Path,
    ) {
        ConsoleContext.offline(mount).run { context ->
            context.startupFailure shouldBe null
            context.environment.getProperty(MEMBERSHIP_KEY, Boolean::class.javaObjectType) shouldBe false
            context.environment.getProperty(READY_KEY) shouldBe "readinessState,vault,db,eventLoops"
        }
        val lines = Files.readAllLines(Path.of("src/main/resources/application.yaml"))
        val at = lines.indexOfFirst { it.trim().startsWith("validate-group-membership:") }
        (at > 0) shouldBe true
        val comment = lines.take(at).takeLastWhile { it.trim().startsWith("#") }.joinToString("\n")
        comment shouldContain "INV-Q-17470"
        comment shouldContain "INV-T-14458"
    }

    private companion object {
        const val MEMBERSHIP_KEY = "management.endpoint.health.validate-group-membership"
        const val READY_KEY = "management.endpoint.health.group.ready.include"
    }
}
