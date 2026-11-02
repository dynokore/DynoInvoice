package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.SealedAccountKeeper
import com.dynokore.gst.access.application.breakglass.SealingCredentials
import com.dynokore.gst.access.application.breakglass.SignInAttempts
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffOutcome
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.gst.access.domain.breakglass.SignInBackoff
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** INV-AC-9184, slice 1: outcome, saved state and delay on a fake port and a fixed clock; no emission is asserted. */
class SignInBackoffUseCaseTest {
    private val start = Instant.parse("2026-11-02T00:00:00Z")
    private var now = start
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?): Clock = this

            override fun instant(): Instant = now
        }
    private val right = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
    private val wrong = "wrong"

    private class Fake : SignInAttempts {
        val states = mutableMapOf<String, SignInBackoff>()
        var saves = 0

        override fun readForUpdate(unlockRef: String) = states[unlockRef] ?: SignInBackoff.INITIAL

        override fun save(
            unlockRef: String,
            state: SignInBackoff,
        ) {
            saves++
            states[unlockRef] = state
        }
    }

    private val fake = Fake()
    private val sessions =
        object : BreakGlassSessions {
            override fun insert(
                sessionHours: Int,
                unlockedAt: Instant,
                approvedBy: List<String>,
            ): BreakGlassSession = error("not used")

            override fun find(ref: String): BreakGlassSession? =
                when (ref) {
                    "BG-1", "BG-2" -> session(ref, false, start.plusSeconds(36000))
                    "BG-SEALED" -> session(ref, true, start.plusSeconds(36000))
                    "BG-EXPIRED" -> session(ref, false, start.minusSeconds(1))
                    else -> null
                }

            override fun markSealed(ref: String) = Unit

            override fun insertAction(
                unlockRef: String,
                action: BreakGlassAction,
                target: String?,
                callerService: String,
            ): String = error("not used")
        }

    private fun session(
        ref: String,
        sealed: Boolean,
        expiresAt: Instant,
    ) = BreakGlassSession(ref, "T-1", 4, start.minusSeconds(60), expiresAt, listOf("a", "b"), sealed)

    private val keeper =
        SealedAccountKeeper(
            object : com.dynokore.gst.access.application.breakglass.BreakGlassAccounts {
                var stored: String? = null

                override fun insertIfAbsent(verifier: String) = (stored == null).also { if (it) stored = verifier }

                override fun verifier() = stored

                override fun replaceVerifier(verifier: String) = false
            },
            SealingCredentials { right },
        ).also { it.createInitial() }

    private val useCase =
        SignInWithBackoffUseCase(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            sessions,
            keeper,
            fake,
            BreakGlassEffects(
                BreakGlassAlerts { _, _, _ -> },
                object : GrantAudit {
                    override fun record(entry: GrantAuditEntry) = Unit
                },
            ),
            clock,
        )

    private fun signIn(
        ref: String,
        credential: String,
    ) = useCase.handle("U-1", ref, credential)

    @Test
    fun `attempts 1 and 2 answer wrong credential with no wait, the 3rd starts a 1 s wait`() {
        assertEquals(SignInWithBackoffOutcome.WrongCredential, signIn("BG-1", wrong))
        assertEquals(SignInWithBackoffOutcome.WrongCredential, signIn("BG-1", wrong))
        assertEquals(SignInBackoff(2, start), fake.states["BG-1"])
        assertEquals(SignInWithBackoffOutcome.WrongCredential, signIn("BG-1", wrong))
        assertEquals(SignInBackoff(3, start.plusSeconds(1)), fake.states["BG-1"])
        assertEquals(SignInWithBackoffOutcome.Waiting(1), signIn("BG-1", wrong))
    }

    @Test
    fun `the delay after failures 1 to 9 and 100 is 0 0 1 2 4 8 16 32 60 and 60 seconds`() {
        val seen = mutableListOf<Long>()
        repeat(9) {
            assertEquals(SignInWithBackoffOutcome.WrongCredential, signIn("BG-1", wrong))
            seen += fake.states.getValue("BG-1").remainingSecondsAt(now)
            now = fake.states.getValue("BG-1").nextTryAt
        }
        assertEquals(listOf(0L, 0, 1, 2, 4, 8, 16, 32, 60), seen)
        fake.states["BG-1"] = SignInBackoff(99, start)
        signIn("BG-1", wrong)
        assertEquals(SignInBackoff(100, now.plusSeconds(60)), fake.states["BG-1"])
    }

    @Test
    fun `while waiting every sign-in right or wrong answers the remaining seconds and the state is byte-equal`() {
        fake.states["BG-1"] = SignInBackoff(5, start.plusMillis(4200))
        val before = fake.states.toMap()
        val saves = fake.saves
        listOf(wrong, right).forEach { assertEquals(SignInWithBackoffOutcome.Waiting(5), signIn("BG-1", it)) }
        now = start.plusMillis(4000)
        assertEquals(SignInWithBackoffOutcome.Waiting(1), signIn("BG-1", right))
        assertEquals(before, fake.states)
        assertEquals(saves, fake.saves)
    }

    @Test
    fun `after the wait the right credential signs in and resets the count`() {
        fake.states["BG-1"] = SignInBackoff(6, start.plusSeconds(4))
        now = start.plusSeconds(4)
        val outcome = signIn("BG-1", right)
        assertEquals(SignInWithBackoffOutcome.SignedIn(sessions.find("BG-1")!!), outcome)
        assertEquals(SignInBackoff.INITIAL, fake.states["BG-1"])
    }

    @Test
    fun `a right credential before the 3rd failure resets the count`() {
        signIn("BG-1", wrong)
        signIn("BG-1", wrong)
        assertEquals(SignInWithBackoffOutcome.SignedIn(sessions.find("BG-1")!!), signIn("BG-1", right))
        assertEquals(SignInBackoff.INITIAL, fake.states["BG-1"])
        signIn("BG-1", wrong)
        signIn("BG-1", wrong)
        assertEquals(SignInBackoff(2, start), fake.states["BG-1"])
    }

    @Test
    fun `each unlock has its own state, a wrong credential for BG-2 while BG-1 waits has no wait`() {
        repeat(3) { signIn("BG-1", wrong) }
        assertEquals(SignInWithBackoffOutcome.Waiting(1), signIn("BG-1", wrong))
        assertEquals(SignInWithBackoffOutcome.WrongCredential, signIn("BG-2", wrong))
        assertEquals(SignInBackoff(1, start), fake.states["BG-2"])
        assertEquals(SignInBackoff(3, start.plusSeconds(1)), fake.states["BG-1"])
    }

    @Test
    fun `an unknown, resealed or expired unlock is decided before the counter and touches no state`() {
        fake.states["BG-SEALED"] = SignInBackoff(5, start.plusSeconds(30))
        assertEquals(SignInWithBackoffOutcome.NotFound, signIn("BG-9", wrong))
        assertEquals(SignInWithBackoffOutcome.NotOpen, signIn("BG-SEALED", wrong))
        assertEquals(SignInWithBackoffOutcome.NotOpen, signIn("BG-EXPIRED", right))
        assertEquals(0, fake.saves)
    }
}
