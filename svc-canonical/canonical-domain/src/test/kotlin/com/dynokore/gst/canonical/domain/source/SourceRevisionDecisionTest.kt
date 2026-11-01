package com.dynokore.gst.canonical.domain.source

import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.source.SourceDecision.Amend
import com.dynokore.gst.canonical.domain.source.SourceDecision.New
import com.dynokore.gst.canonical.domain.source.SourceDecision.Refused
import com.dynokore.gst.canonical.domain.source.SourceDecision.Replay
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-14188: the decision of an arriving source event (INV-REQ-793). */
class SourceRevisionDecisionTest {
    private val doc = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val entity = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val identity = SourceIdentity(entity, Direction.SALE, 380)

    private fun recorded(vararg revisions: Long) = RecordedSource(doc, revisions.toSet(), identity)

    private fun decide(
        recorded: RecordedSource?,
        revision: Long,
        arriving: SourceIdentity = identity,
    ) = SourceRevisionDecision.decide(recorded, revision, arriving)

    @Test
    fun `INV-AC-14188 not recorded gives New for revision 0 and 7`() {
        decide(null, 0) shouldBe New
        decide(null, 7) shouldBe New
    }

    @Test
    fun `INV-AC-14188 a higher revision with equal identity is an Amend of the recorded doc`() {
        decide(recorded(1), 2) shouldBe Amend(doc)
    }

    @Test
    fun `INV-AC-14188 replay of an earlier revision is not a refusal`() {
        decide(recorded(1, 2), 1) shouldBe Replay(doc)
    }

    @Test
    fun `INV-AC-14188 replay does not compare identity`() {
        decide(recorded(1, 2), 2, identity.copy(direction = Direction.PURCHASE)) shouldBe Replay(doc)
    }

    @Test
    fun `INV-AC-14188 a revision below the highest is refused`() {
        decide(recorded(2), 1) shouldBe Refused(SourceRefusal.REVISION_BELOW_RECORDED)
    }

    @Test
    fun `INV-AC-14188 revision check comes before identity check`() {
        decide(recorded(2), 1, identity.copy(entityId = UUID(0, 9))) shouldBe
            Refused(SourceRefusal.REVISION_BELOW_RECORDED)
    }

    @Test
    fun `INV-AC-14188 a changed entity is refused`() {
        decide(recorded(1, 2), 3, identity.copy(entityId = UUID(0, 9))) shouldBe
            Refused(SourceRefusal.IDENTITY_CHANGED)
    }

    @Test
    fun `INV-AC-14188 a changed direction is refused`() {
        decide(recorded(1, 2), 3, identity.copy(direction = Direction.PURCHASE)) shouldBe
            Refused(SourceRefusal.IDENTITY_CHANGED)
    }

    @Test
    fun `INV-AC-14188 a changed document type is refused`() {
        decide(recorded(1, 2), 3, identity.copy(documentTypeCode = 381)) shouldBe
            Refused(SourceRefusal.IDENTITY_CHANGED)
    }

    @Test
    fun `INV-AC-14188 a gap in the set replays a member and refuses a non-member below the highest`() {
        decide(recorded(1, 3), 3) shouldBe Replay(doc)
        decide(recorded(1, 3), 2) shouldBe Refused(SourceRefusal.REVISION_BELOW_RECORDED)
    }

    @Test
    fun `INV-AC-14188 a negative revision is rejected`() {
        shouldThrow<IllegalArgumentException> { decide(null, -1) }
    }

    @Test
    fun `INV-AC-14188 an empty revisions set is rejected`() {
        shouldThrow<IllegalArgumentException> { RecordedSource(doc, emptySet(), identity) }
    }

    @Test
    fun `INV-AC-14188 structure pin decide exists and takes three parameters`() {
        val decides =
            SourceRevisionDecision::class.java.declaredMethods
                .filter { !it.isSynthetic && it.name == "decide" }
        decides.shouldNotBeEmpty()
        decides.all { it.parameterCount == 3 } shouldBe true
    }
}
