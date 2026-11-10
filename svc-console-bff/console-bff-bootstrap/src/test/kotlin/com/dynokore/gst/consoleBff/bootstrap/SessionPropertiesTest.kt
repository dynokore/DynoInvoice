package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/** The session timeouts (INV-Q-14429): defaults from application.yaml, five failures naming the property. */
class SessionPropertiesTest {
    @Test
    fun `INV-AC-14457 the default context binds 30 minutes and 12 hours`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val p = context.getBean(ConsoleSessionProperties::class.java)
            p.idleTimeout shouldBe Duration.ofMinutes(30)
            p.absoluteTimeout shouldBe Duration.ofHours(12)
            p.sweepInterval shouldBe Duration.ofMinutes(5)
        }
    }

    @Test
    fun `INV-AC-14457 a run with idle-timeout PT10M binds 10 minutes`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir, "console.session.idle-timeout=PT10M").run { context ->
            context.getBean(ConsoleSessionProperties::class.java).idleTimeout shouldBe Duration.ofMinutes(10)
        }
    }

    @Test
    fun `INV-AC-14457 a missing value fails naming the property`(
        @TempDir dir: Path,
    ) = fails(dir, "console.session.idle-timeout=", "idle-timeout")

    @Test
    fun `INV-AC-14457 a zero value fails naming the property`(
        @TempDir dir: Path,
    ) = fails(dir, "console.session.absolute-timeout=PT0S", "console.session.absolute-timeout")

    @Test
    fun `INV-AC-14457 a negative value fails naming the property`(
        @TempDir dir: Path,
    ) = fails(dir, "console.session.idle-timeout=-PT5M", "console.session.idle-timeout")

    @Test
    fun `INV-AC-14457 an unparsable value fails naming the property`(
        @TempDir dir: Path,
    ) = fails(dir, "console.session.idle-timeout=soon", "idle-timeout")

    @Test
    fun `INV-AC-14457 idle greater than absolute fails naming the property`(
        @TempDir dir: Path,
    ) = fails(dir, "console.session.idle-timeout=PT13H", "console.session.idle-timeout")

    @Test
    fun `INV-AC-14457 a zero sweep interval fails naming the property`(
        @TempDir dir: Path,
    ) = fails(dir, "console.session.sweep-interval=PT0S", "console.session.sweep-interval")

    private fun fails(
        dir: Path,
        property: String,
        named: String,
    ) {
        ConsoleContext.offline(dir, property).run { context ->
            val failure = checkNotNull(context.startupFailure)
            generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
                named
        }
    }
}
