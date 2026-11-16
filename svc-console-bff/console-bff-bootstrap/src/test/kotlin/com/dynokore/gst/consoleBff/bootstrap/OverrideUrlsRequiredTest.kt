package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

/** INV-AC-21206: both owner URLs of overrideException are required, not blank and not an unresolved placeholder. */
class OverrideUrlsRequiredTest {
    private val properties = listOf("console.payload-svc-url", "console.submission-svc-url")

    @Test
    fun `INV-AC-21206 the context starts with both URLs set`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { (it.startupFailure == null) shouldBe true }
    }

    @Test
    fun `INV-AC-21206 the context fails when either URL is blank or an unresolved placeholder`(
        @TempDir dir: Path,
    ) {
        for (property in properties) {
            for (value in listOf("", "   ", "\${CONSOLE_UNSET_IN_TEST}", "http://\${HOST}")) {
                ConsoleContext
                    .offline(dir, "$property=$value")
                    .run { (it.startupFailure != null) shouldBe true }
            }
        }
    }

    @Test
    fun `INV-AC-21206 the context fails when either URL is absent from the configuration`(
        @TempDir dir: Path,
    ) {
        for (property in properties) {
            // application.yaml holds only the unresolved placeholder: with no environment value the start fails.
            val others = ConsoleContext.overrides(dir).filterNot { it.startsWith("$property=") }
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ConsoleBffApplication::class.java)
                .withPropertyValues(*others.toTypedArray())
                .run { (it.startupFailure != null) shouldBe true }
        }
    }

    @Test
    fun `INV-AC-21206 application yaml on the classpath gives the two properties no default`() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/application.yaml")) { "application.yaml is not on the classpath" }
                .use { String(it.readBytes()) }
        val lines = text.lines().map { it.substringBefore(" #").trim() }
        lines.count { it == "payload-svc-url: \${CONSOLE_PAYLOAD_SVC_URL}" } shouldBe 1
        lines.count { it == "submission-svc-url: \${CONSOLE_SUBMISSION_SVC_URL}" } shouldBe 1
    }
}
