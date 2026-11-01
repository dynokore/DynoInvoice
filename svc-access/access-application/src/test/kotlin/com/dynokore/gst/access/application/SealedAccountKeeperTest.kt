package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.breakglass.BreakGlassAccounts
import com.dynokore.gst.access.application.breakglass.SealedAccountKeeper
import com.dynokore.gst.access.application.breakglass.SealedAccountRules
import com.dynokore.gst.access.application.breakglass.SealingCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** INV-AC-8899 at the unit level: the credential format, the lower-case hex digest and the constant-time check. */
class SealedAccountKeeperTest {
    private class Held : BreakGlassAccounts {
        var stored: String? = null

        override fun insertIfAbsent(verifier: String): Boolean = (stored == null).also { if (it) stored = verifier }

        override fun verifier(): String? = stored

        override fun replaceVerifier(verifier: String): Boolean = (stored != null).also { if (it) stored = verifier }
    }

    private fun bytes(count: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(count) { 7 })

    @Test
    fun `INV-AC-8899 a credential of 31 bytes from a test double fails the length assertion and stores nothing`() {
        val held = Held()
        val keeper = SealedAccountKeeper(held, SealingCredentials { bytes(31) })
        val failure = assertThrows(IllegalStateException::class.java) { keeper.createInitial() }
        assertTrue(failure.message!!.contains("at least 32"))
        assertNull(held.stored)
        // 32 bytes pass; the stored value is the lower-case hex SHA-256 digest of the credential
        val ok = SealedAccountKeeper(held, SealingCredentials { bytes(32) }).createInitial()
        assertEquals(SealedAccountRules.digestOf(bytes(32)), held.stored)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(held.stored!!))
        assertFalse(ok!!.toString().contains(bytes(32)))
    }

    @Test
    fun `INV-AC-8899 a padded or non base64url credential fails and a second creation keeps the first`() {
        val held = Held()
        listOf(bytes(32) + "=", "a+b/".repeat(16)).forEach { bad ->
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    SealedAccountKeeper(held, SealingCredentials { bad }).createInitial()
                }
            assertTrue(failure.message!!.contains("base64url"))
        }
        val keeper = SealedAccountKeeper(held, SealingCredentials { bytes(40) })
        assertNotNull(keeper.createInitial())
        val first = held.stored
        assertNull(keeper.createInitial())
        assertEquals(first, held.stored)
    }

    @Test
    fun `INV-AC-8899 the check compares digests and the digest itself is not a credential`() {
        val held = Held()
        val keeper = SealedAccountKeeper(held, SealingCredentials { bytes(33) })
        keeper.createInitial()
        assertTrue(keeper.matches(bytes(33)))
        assertFalse(keeper.matches(held.stored!!))
        assertFalse(keeper.matches(bytes(33).dropLast(1)))
        // no account row: fail closed, and a rotation changes nothing
        val empty = SealedAccountKeeper(Held(), SealingCredentials { bytes(33) })
        assertFalse(empty.matches(bytes(33)))
        assertNull(empty.rotate())
    }
}
