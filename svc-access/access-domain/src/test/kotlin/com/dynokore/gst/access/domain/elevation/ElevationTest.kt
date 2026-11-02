package com.dynokore.gst.access.domain.elevation

import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ElevationTest {
    private val now = Instant.parse("2026-11-02T08:00:00Z")
    private val op = IdentityRef("user:op")
    private val officer = IdentityRef("user:officer")

    private val ten1 = TenantRef("TEN-1")

    private fun pending(kind: ElevationKind = ElevationKind.ELEVATION) =
        ElevationRecord.pending(
            kind.refPrefix + "1",
            ten1,
            ElevationAsk(kind, if (kind == ElevationKind.ELEVATION) ten1.value else "INST-1", op, "CASE-1"),
        )

    @Test
    fun `a pending record opens nothing`() {
        assertFalse(pending().opensAccessAt(now))
    }

    @Test
    fun `a granted record opens access inside its time box only`() {
        val granted = checkNotNull(pending().granted(officer, now, Duration.ofMinutes(30)))
        assertTrue(granted.opensAccessAt(now))
        assertFalse(granted.opensAccessAt(now.plus(Duration.ofMinutes(30))))
        assertFalse(granted.opensAccessAt(now.minusSeconds(1)))
    }

    @Test
    fun `only a pending record can be decided or withdrawn`() {
        val granted = checkNotNull(pending().granted(officer, now, Duration.ofMinutes(5)))
        assertNull(granted.withdrawn())
        assertNull(granted.refused(officer))
        assertNull(granted.granted(officer, now, Duration.ofMinutes(5)))
        assertFalse(checkNotNull(pending().withdrawn()).opensAccessAt(now))
        assertEquals(ElevationStatus.REFUSED, checkNotNull(pending().refused(officer)).status)
    }

    @Test
    fun `a zero or negative time box is refused`() {
        assertNull(pending().granted(officer, now, Duration.ZERO))
        assertNull(pending().granted(officer, now, Duration.ofMinutes(-1)))
    }

    @Test
    fun `operator access matches the requester, the tenant and the kind by equality`() {
        val elv = checkNotNull(pending().granted(officer, now, Duration.ofMinutes(30)))
        assertTrue(OperatorAccess.elevated(listOf(elv), op, ten1, now))
        assertFalse(OperatorAccess.elevated(listOf(elv), op, TenantRef("TEN-10"), now))
        assertFalse(OperatorAccess.elevated(listOf(elv), IdentityRef("user:op2"), ten1, now))
        assertFalse(OperatorAccess.invited(listOf(elv), op, ten1, now))
        val inv =
            checkNotNull(pending(ElevationKind.DIAGNOSTIC_INVITATION).granted(officer, now, Duration.ofMinutes(30)))
        assertTrue(OperatorAccess.invited(listOf(inv), op, ten1, now))
        assertFalse(OperatorAccess.invited(listOf(inv), op, TenantRef("TEN-2"), now))
        assertFalse(OperatorAccess.invited(listOf(pending(ElevationKind.DIAGNOSTIC_INVITATION)), op, ten1, now))
        assertFalse(
            OperatorAccess.invited(
                listOf(checkNotNull(pending(ElevationKind.DIAGNOSTIC_INVITATION).withdrawn())),
                op,
                ten1,
                now,
            ),
        )
    }
}
