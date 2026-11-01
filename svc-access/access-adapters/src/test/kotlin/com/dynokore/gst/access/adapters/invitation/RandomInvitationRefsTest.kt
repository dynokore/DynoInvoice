package com.dynokore.gst.access.adapters.invitation

import com.dynokore.gst.access.application.invitation.InvitationRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-Q-14264 A: `invitation:` + the reference fits the 64-character CHECK; the reference has 128 random bits. */
class RandomInvitationRefsTest {
    @Test
    fun `10000 references have at most 48 characters, start with INVN- and do not repeat`() {
        val refs = RandomInvitationRefs()
        val minted = List(10_000) { refs.next() }
        assertTrue(minted.all { it.length <= InvitationRefs.MAX_LENGTH && it.startsWith("INVN-") })
        assertTrue(minted.all { ("invitation:$it").length <= 64 })
        assertEquals(10_000, minted.toSet().size)
        // 128 random bits as unpadded base64url are 22 characters
        assertTrue(minted.all { it.length - "INVN-".length >= 14 })
    }
}
