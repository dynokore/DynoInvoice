package com.dynokore.gst.evidence.application.auditedread

import com.dynokore.gst.evidence.domain.auditedread.AuditedCall
import com.dynokore.gst.evidence.domain.auditedread.AuditedCall.AcknowledgementChain
import com.dynokore.gst.evidence.domain.auditedread.AuditedReadScope
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriteFailed
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class AuditedReadsTest {
    private class Fake(
        private val failure: Throwable? = null,
    ) : AuditedOperations {
        val entries = mutableListOf<AuditEntry>()
        val order = mutableListOf<String>()

        override fun <T> change(
            entry: AuditEntry,
            context: EventContext,
            block: () -> T,
        ): T = error("change is not used by an audited read")

        override fun <T> read(
            entry: AuditEntry,
            context: EventContext,
            block: () -> T,
        ): T {
            entries += entry
            order += "entry"
            failure?.let { throw it }
            return block()
        }
    }

    private val context =
        EventContext(
            TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            EntityRef("E-001"),
            null,
            null,
            "agg-1",
            null,
            CorrelationId("corr-1"),
            CausationId("cause-1"),
        )
    private val call: AuditedCall = AcknowledgementChain("ACK-1/2")

    @Test
    fun `INV-AC-758 perform writes the entry with actor, action and scope and returns the block result`() {
        val fake = Fake()
        val result =
            AuditedReads(fake).perform(call, "auditor-1", context) {
                fake.order += "block"
                42
            }
        result shouldBe 42
        fake.order shouldBe listOf("entry", "block")
        fake.entries shouldBe
            listOf(AuditEntry("auditor-1", "getAcknowledgementChain", "acknowledgement:ACK-1%2F2"))
        val entry = fake.entries.single()
        entry.reason shouldBe null
        entry.priorStateJson shouldBe null
        AuditedReadScope.entryOf(call).scope shouldBe entry.scope
    }

    @Test
    fun `a failed audit write reaches the caller and no result is returned`() {
        val failure = AuditWriteFailed(RuntimeException("store down"))
        var ran = false
        val thrown =
            shouldThrow<AuditWriteFailed> {
                AuditedReads(Fake(failure)).perform(call, "auditor-1", context) {
                    ran = true
                    "answer"
                }
            }
        (thrown === failure) shouldBe true
        ran shouldBe false
    }

    @Test
    fun `a blank actor is refused before the audited read is called and the block does not run`() {
        for (actor in listOf("", " ")) {
            val fake = Fake()
            var ran = false
            shouldThrow<IllegalArgumentException> {
                AuditedReads(fake).perform(call, actor, context) { ran = true }
            }
            fake.entries shouldBe emptyList()
            ran shouldBe false
        }
    }

    @Test
    fun `a call refused by the scope rules writes nothing and the block does not run`() {
        val fake = Fake()
        var ran = false
        shouldThrow<IllegalArgumentException> {
            AuditedReads(fake).perform(AcknowledgementChain(" "), "auditor-1", context) { ran = true }
        }
        fake.entries shouldBe emptyList()
        ran shouldBe false
    }
}
