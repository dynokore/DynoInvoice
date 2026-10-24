package com.dynokore.gst.notification.application

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchesTest {
    private fun subscription(
        eventType: String,
        vararg entities: String,
    ) = WebhookSubscription(UUID.randomUUID(), eventType, "secret://endpoint-1", entities.toList())

    @Test
    fun `INV-AC-9527 the four decisions of the criterion`() {
        val s = subscription("document.rejected", "E-001")
        listOf(
            matches(s, "document.rejected", "E-001"),
            matches(s, "document.rejected", "E-0011"),
            matches(s, "document.rejected", "E-002"),
            matches(s, "document.acknowledged", "E-001"),
        ) shouldBe listOf(true, false, false, false)
    }

    @Test
    fun `INV-AC-2038 an entity outside the scope or a look-alike matches no subscription`() {
        val s = subscription("document.acknowledged", "E")
        matches(s, "document.acknowledged", "F") shouldBe false
        matches(s, "document.acknowledged", "E") shouldBe true
        matches(s, "document.acknowledged", "E-001") shouldBe false
        matches(s, "document.acknowledged", "e") shouldBe false
        matches(s, "Document.Acknowledged", "E") shouldBe false
        matches(s, "document.acknowledged", "") shouldBe false
        matches(subscription("document.acknowledged"), "document.acknowledged", "E") shouldBe false
    }

    @Test
    fun `a prefix of the entity reference or of the event type does not match`() {
        val s = subscription("document.rejected", "E-0011")
        matches(s, "document.rejected", "E-001") shouldBe false
        matches(s, "document.rejec", "E-0011") shouldBe false
    }
}
