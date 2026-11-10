package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** INV-Q-17470: the temporary `validate-group-membership` exception is gone; the ready group validates membership. */
class GroupMembershipGateTest {
    @Test
    fun `INV-AC-14457 the key validate-group-membership is absent from application yaml and the context starts`(
        @TempDir mount: Path,
    ) {
        val text = Files.readString(Path.of("src/main/resources/application.yaml"))
        text.contains("validate-group-membership") shouldBe false
        ConsoleContext.online(mount).run { context ->
            context.startupFailure shouldBe null
            context.environment.containsProperty(MEMBERSHIP_KEY) shouldBe false
        }
    }

    @Test
    fun `INV-AC-14457 a context with no DataSource bean fails to start because the ready group names db`(
        @TempDir mount: Path,
    ) {
        ConsoleContext
            .offline(
                mount,
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            ).run { context -> (context.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-14457 no uncommented line of application yaml names the key`() {
        val live =
            Files
                .readAllLines(
                    Path.of("src/main/resources/application.yaml"),
                ).filterNot { it.trim().startsWith("#") }
        live.none { it.contains("validate-group-membership") } shouldBe true
    }

    private companion object {
        const val MEMBERSHIP_KEY = "management.endpoint.health.validate-group-membership"
    }
}
