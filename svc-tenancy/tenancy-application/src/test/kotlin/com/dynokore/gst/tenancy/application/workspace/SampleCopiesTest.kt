package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.domain.SampleRow
import com.dynokore.gst.tenancy.domain.TenantRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SampleCopiesTest {
    private val productionId = TenantRefs.idOf("acme")
    private val context = InMemoryTenantContext(productionId)
    private val workspaceRef = TenantRefs.refOfWorkspace("acme", "uat")
    private val store =
        InMemoryWorkspaceStore(context, TenantRecord(productionId.value, "acme", "Acme", "plan", "c-1")).also {
            CreateWorkspace(context, DirectTransactions, it).handle("acme", "uat")
        }
    private val approvals = InMemoryApprovals()
    private val rows =
        listOf(SampleRow("Acme", "UEN-1", BigDecimal("5.00")), SampleRow("Beta", "UEN-2", BigDecimal("7")))
    private val sink = RecordingTransfer(rows)
    private val grants =
        setOf(
            "approver" to SampleCopyPermissions.APPROVE,
            "requester" to SampleCopyPermissions.REQUEST,
        )

    private fun copies(on: Boolean) =
        SampleCopies(DirectTransactions, store, { on }, approvals, InMemoryAuthority(grants), sink.transfer)

    @Test
    fun `a copy is refused while the policy is off`() {
        assertEquals(SampleApprovalOutcome.SamplesOff, copies(false).approve("approver", "s1"))
        assertEquals(
            SampleCopyOutcome.Refused(SampleCopyRefusal.SAMPLES_OFF),
            copies(false).copy("requester", workspaceRef, "s1"),
        )
        assertTrue(sink.copied.isEmpty())
    }

    @Test
    fun `an approval is spent by one copy and cannot authorise a second`() {
        val c = copies(true)
        assertEquals(SampleApprovalOutcome.Recorded("approver"), c.approve("approver", "s1"))
        assertEquals("approver", approvals.open("s1"))
        assertEquals(SampleCopyOutcome.Copied(2), c.copy("requester", workspaceRef, "s1"))
        assertEquals(
            SampleCopyOutcome.Refused(SampleCopyRefusal.NOT_APPROVED),
            c.copy("requester", workspaceRef, "s1"),
        )
        assertEquals(1, sink.copied.size)
        assertEquals(
            listOf(BigDecimal("5.00"), BigDecimal("7")),
            sink.copied
                .single()
                .third
                .map { it.amount },
        )
        assertTrue(
            sink.copied.single().third.none {
                it.counterpartyName == "Acme" ||
                    it.counterpartyId.startsWith("UEN")
            },
        )
    }

    @Test
    fun `a copy without an approval is refused`() {
        assertEquals(
            SampleCopyOutcome.Refused(SampleCopyRefusal.NOT_APPROVED),
            copies(true).copy("requester", workspaceRef, "s1"),
        )
        assertTrue(sink.copied.isEmpty())
    }

    @Test
    fun `a caller without the grant records nothing and copies nothing`() {
        val c = copies(true)
        assertEquals(SampleApprovalOutcome.Forbidden, c.approve("requester", "s1"))
        assertTrue(approvals.open.isEmpty())
        c.approve("approver", "s1")
        assertEquals(SampleCopyOutcome.Forbidden, c.copy("approver", workspaceRef, "s1"))
        assertTrue(sink.copied.isEmpty())
        assertEquals("approver", approvals.open("s1"))
    }

    @Test
    fun `the look-alike key sample-copy dot approved is refused as an unknown key`() {
        val authority = InMemoryAuthority(grants + ("approver" to "sample-copy.approved"))
        val refused = runCatching { authority.holds("approver", "sample-copy.approved") }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertEquals(
            setOf("sample-copy.approve", "sample-copy.request"),
            setOf(SampleCopyPermissions.APPROVE, SampleCopyPermissions.REQUEST),
        )
    }

    @Test
    fun `the approval record stores the acting user id`() {
        copies(true).approve("approver", "s1")
        assertEquals(listOf("s1" to "approver"), approvals.open)
    }
}
