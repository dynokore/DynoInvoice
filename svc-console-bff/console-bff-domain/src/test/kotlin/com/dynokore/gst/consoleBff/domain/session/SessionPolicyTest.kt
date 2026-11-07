package com.dynokore.gst.consoleBff.domain.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class SessionPolicyTest {
    private val idle = Duration.ofSeconds(1800)
    private val absolute = Duration.ofSeconds(43200)
    private val policy = SessionPolicy(idle, absolute)
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `INV-AC-14455 policy table at the boundaries of both timeouts`() {
        val s = Duration.ofSeconds(1)
        val rows =
            listOf(
                Triple(t0, t0.plusSeconds(1800).minus(s), t0.plusSeconds(1800).minus(s)) to SessionVerdict.Valid,
                Triple(t0, t0, t0.plusSeconds(1800)) to SessionVerdict.IdleExpired,
                Triple(t0, t0.plusSeconds(43199), t0.plusSeconds(43199)) to SessionVerdict.Valid,
                Triple(t0, t0.plusSeconds(43199), t0.plusSeconds(43200)) to SessionVerdict.AbsoluteExpired,
                Triple(t0, t0.plusSeconds(43199), t0.plusSeconds(43199).plusNanos(0)) to SessionVerdict.Valid,
                Triple(t0, t0, t0.plusSeconds(43200)) to SessionVerdict.AbsoluteExpired,
                Triple(t0, t0.plusSeconds(100), t0.plusSeconds(100).minusNanos(1)) to SessionVerdict.ClockSkew,
                Triple(t0, t0, t0.minusNanos(1)) to SessionVerdict.ClockSkew,
                Triple(t0, t0, t0.plusSeconds(1800).minusNanos(1)) to SessionVerdict.Valid,
            )
        for ((input, expected) in rows) {
            policy.evaluate(input.first, input.second, input.third) shouldBe expected
        }
    }

    @Test
    fun `INV-AC-14455 absolute wins when idle also fires and activity one second ago does not save it`() {
        policy.evaluate(t0, t0.plusSeconds(43199), t0.plusSeconds(43200)) shouldBe SessionVerdict.AbsoluteExpired
        policy.evaluate(t0, t0, t0.plusSeconds(50000)) shouldBe SessionVerdict.AbsoluteExpired
    }

    @Test
    fun `INV-AC-14455 now before openedAt alone is ClockSkew`() {
        val opened = t0.plusSeconds(100)
        policy.evaluate(opened, t0.plusSeconds(50), t0.plusSeconds(60)) shouldBe SessionVerdict.ClockSkew
        policy.evaluate(opened, t0.plusSeconds(50), opened.minusNanos(1)) shouldBe SessionVerdict.ClockSkew
    }

    @Test
    fun `INV-AC-14455 invalid limits are refused`() {
        shouldThrow<IllegalArgumentException> { SessionPolicy(Duration.ZERO, absolute) }
        shouldThrow<IllegalArgumentException> { SessionPolicy(Duration.ofSeconds(-1), absolute) }
        shouldThrow<IllegalArgumentException> { SessionPolicy(idle, Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { SessionPolicy(idle, Duration.ofSeconds(-1)) }
        shouldThrow<IllegalArgumentException> { SessionPolicy(absolute.plusSeconds(1), absolute) }
        SessionPolicy(absolute, absolute).evaluate(t0, t0, t0) shouldBe SessionVerdict.Valid
    }

    @Test
    fun `INV-AC-14455 no preset literal in the main sources of domain and application`() {
        val banned = listOf("Duration.ofMinutes(30)", "Duration.ofHours(12)", "PT30M", "PT12H")
        val roots = listOf(Path.of("src/main"), Path.of("../console-bff-application/src/main"))
        var scanned = 0
        for (root in roots) {
            Files.walk(root).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { f ->
                    scanned++
                    val text = Files.readString(f)
                    for (b in banned) {
                        (text.contains(b)) shouldBe false
                    }
                }
            }
        }
        (scanned >= 2) shouldBe true
        // the check itself fires on a literal
        ("val d = Duration.ofMinutes(30)".contains(banned[0])) shouldBe true
    }
}
