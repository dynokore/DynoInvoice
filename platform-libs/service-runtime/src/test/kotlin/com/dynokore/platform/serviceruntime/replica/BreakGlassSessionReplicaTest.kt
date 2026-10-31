package com.dynokore.platform.serviceruntime.replica

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** INV-AC-9303 (c), (d), (e) and INV-AC-9304 (e), (f), (g) for the replica half of INV-Q-9119 A. */
class BreakGlassSessionReplicaTest {
    private val clock = MutableClock()
    private val counted = mutableListOf<String>()
    private val replica = BreakGlassSessionReplica(clock) { counted += it }
    private val expires: Instant = clock.instant().plus(Duration.ofHours(4))

    private fun event(
        status: String,
        unlockRef: String = "BG-1",
        expiresAt: Instant = expires,
        approvedBy: List<String> = listOf("identity:USR-004", "identity:USR-005"),
    ) = BreakGlassSessionChanged(unlockRef, TENANT_A, status, expiresAt, approvedBy)

    private fun session(
        status: BreakGlassStatus,
        unlockRef: String = "BG-1",
        expiresAt: Instant = expires,
    ) = BreakGlassSession(unlockRef, TENANT_A, status, expiresAt, listOf("identity:USR-004", "identity:USR-005"))

    @Test
    fun `OPEN stores the session and restricts an approver before expiry`() {
        replica.accept(event("OPEN"))
        replica.held(TENANT_A, "BG-1") shouldBe session(BreakGlassStatus.OPEN)
        replica.restrictedSession(TENANT_A, "identity:USR-004", clock.instant()) shouldBe session(BreakGlassStatus.OPEN)
    }

    @Test
    fun `a second OPEN is ignored, also with another expiry`() {
        replica.accept(event("OPEN"))
        replica.accept(event("OPEN"))
        replica.accept(event("OPEN", expiresAt = expires.plusSeconds(3600)))
        replica.held(TENANT_A, "BG-1") shouldBe session(BreakGlassStatus.OPEN)
        counted shouldBe emptyList()
    }

    @Test
    fun `SEALED then OPEN stays sealed and restricts nobody`() {
        replica.accept(event("SEALED"))
        replica.accept(event("OPEN"))
        replica.held(TENANT_A, "BG-1") shouldBe session(BreakGlassStatus.SEALED)
        replica.restrictedSession(TENANT_A, "identity:USR-004", clock.instant()) shouldBe null
    }

    @Test
    fun `SEALED replaces an open session`() {
        replica.accept(event("OPEN"))
        replica.accept(event("SEALED"))
        replica.held(TENANT_A, "BG-1") shouldBe session(BreakGlassStatus.SEALED)
        replica.restrictedSession(TENANT_A, "identity:USR-004", clock.instant()) shouldBe null
    }

    @Test
    fun `another status text is ignored, the held state kept and the event counted`() {
        replica.accept(event("OPEN"))
        replica.accept(event("SUSPENDED"))
        replica.accept(event("open"))
        replica.held(TENANT_A, "BG-1") shouldBe session(BreakGlassStatus.OPEN)
        counted shouldBe listOf("break_glass_event_ignored_total", "break_glass_event_ignored_total")
        BreakGlassSessionReplica(clock).also { it.accept(event("SUSPENDED")) }.held(TENANT_A, "BG-1") shouldBe null
    }

    @Test
    fun `the call at exactly expiresAt is not restricted and one second before it is`() {
        replica.accept(event("OPEN"))
        replica.restrictedSession(TENANT_A, "identity:USR-004", expires.minusSeconds(1)) shouldBe
            session(BreakGlassStatus.OPEN)
        replica.restrictedSession(TENANT_A, "identity:USR-004", expires) shouldBe null
    }

    @Test
    fun `an entry at or before the clock instant is dropped on the next call`() {
        replica.accept(event("OPEN"))
        clock.advance(Duration.ofHours(4))
        replica.held(TENANT_A, "BG-1") shouldBe null
    }

    @Test
    fun `a look-alike actor, another tenant and no session are not restricted`() {
        replica.accept(event("OPEN"))
        replica.restrictedSession(TENANT_A, "identity:USR-0041", clock.instant()) shouldBe null
        replica.restrictedSession(TENANT_A, "identity:USR-00", clock.instant()) shouldBe null
        replica.restrictedSession(TENANT_B, "identity:USR-004", clock.instant()) shouldBe null
    }

    @Test
    fun `the open session with the latest expiry wins, a tie goes to the smaller unlockRef`() {
        replica.accept(event("OPEN", "BG-2", expires))
        replica.accept(event("OPEN", "BG-1", expires))
        replica.restrictedSession(TENANT_A, "identity:USR-004", clock.instant())?.unlockRef shouldBe "BG-1"
        replica.accept(event("OPEN", "BG-3", expires.plusSeconds(1)))
        replica.restrictedSession(TENANT_A, "identity:USR-004", clock.instant())?.unlockRef shouldBe "BG-3"
    }
}
