package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.application.alerting.DefaultRecipientResult.NoRecipient
import com.dynokore.gst.notification.application.alerting.DefaultRecipientResult.Recipients
import com.dynokore.gst.notification.domain.alerting.DefaultAlertKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class ResolveDefaultRecipientsTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val t3 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    private val t4 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000004"))

    private class FakeHolders(
        private val table: Map<Pair<TenantId, String>, List<String>>,
    ) : RoleHolders {
        val calls = mutableListOf<Pair<TenantId, String>>()

        override fun holders(
            tenant: TenantId,
            role: String,
        ): List<String> {
            calls += tenant to role
            return table[tenant to role].orEmpty()
        }
    }

    private val port =
        FakeHolders(
            mapOf(
                (t1 to "integration.engineer") to listOf("b", "a"),
                (t1 to "tax.operator") to listOf("x"),
                (t2 to "integration.engineer") to listOf("z"),
                (t2 to "tenant.admin") to listOf("other"),
                (t3 to "tenant.admin") to listOf("t"),
            ),
        )
    private val service = ResolveDefaultRecipients(port)

    @Test
    fun `INV-AC-26001 feed stall reaches integration engineer`() {
        service.resolve(t1, DefaultAlertKind.FEED_STALL) shouldBe Recipients(listOf("a", "b"))
        port.calls.map { it.first }.toSet() shouldBe setOf(t1)
    }

    @Test
    fun `INV-AC-26001 no holder falls back`() {
        service.resolve(t3, DefaultAlertKind.FEED_STALL) shouldBe Recipients(listOf("t"))
        port.calls shouldBe listOf(t3 to "integration.engineer", t3 to "tenant.admin")
    }

    @Test
    fun `INV-AC-26001 never dropped`() {
        service.resolve(t4, DefaultAlertKind.FEED_STALL) shouldBe NoRecipient(DefaultAlertKind.FEED_STALL)
        port.calls.map { it.first }.toSet() shouldBe setOf(t4)
    }

    @Test
    fun `INV-AC-26001 duplicates collapse and order is ascending`() {
        val dup = ResolveDefaultRecipients { _, _ -> listOf("b", "a", "b") }
        dup.resolve(t1, DefaultAlertKind.PLAN_LIMIT) shouldBe Recipients(listOf("a", "b"))
    }
}
