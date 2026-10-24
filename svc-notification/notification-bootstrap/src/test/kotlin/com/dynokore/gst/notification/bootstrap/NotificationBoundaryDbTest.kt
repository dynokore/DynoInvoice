package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.WebhookSubscription
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.WebhookSubscriptionScope
import com.dynokore.gst.notification.application.WebhookSubscriptions
import com.dynokore.gst.notification.application.matches
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID
import com.dynokore.gst.notification.application.WebhookSubscription as Subscription

/**
 * The service boundary against a real database (P109): the full TPL-18 context over the `notification_runtime` login.
 * Subscriptions are read for the bound tenant only and matched by the pure decision (INV-REQ-820), events leave through
 * the outbox in the command transaction and only the catalogued types (INV-REQ-815), and a tenant reads only its own
 * rows (CS-19).
 */
class NotificationBoundaryDbTest {
    private val actor = "svc:notification-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val subscriptions: WebhookSubscriptions = context.getBean(WebhookSubscriptions::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        NotificationContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun Boundary.store(
        tenant: UUID,
        eventType: String,
        vararg entities: String,
    ): UUID {
        val id = UUID.randomUUID()
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction {
                dsl
                    .insertInto(WebhookSubscription.WEBHOOK_SUBSCRIPTION)
                    .set(WebhookSubscription.WEBHOOK_SUBSCRIPTION.ID, id)
                    .set(WebhookSubscription.WEBHOOK_SUBSCRIPTION.TENANT_ID, tenant)
                    .set(WebhookSubscription.WEBHOOK_SUBSCRIPTION.EVENT_TYPE, eventType)
                    .set(WebhookSubscription.WEBHOOK_SUBSCRIPTION.ENDPOINT_REF, "secret://endpoint-1")
                    .execute()
                entities.forEach { entity ->
                    dsl
                        .insertInto(WebhookSubscriptionScope.WEBHOOK_SUBSCRIPTION_SCOPE)
                        .set(WebhookSubscriptionScope.WEBHOOK_SUBSCRIPTION_SCOPE.SUBSCRIPTION_ID, id)
                        .set(WebhookSubscriptionScope.WEBHOOK_SUBSCRIPTION_SCOPE.TENANT_ID, tenant)
                        .set(WebhookSubscriptionScope.WEBHOOK_SUBSCRIPTION_SCOPE.ENTITY_REF, entity)
                        .execute()
                }
            }
        }
        return id
    }

    private fun Boundary.read(tenant: UUID): List<Subscription> =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { subscriptions.ofBoundTenant() } }

    @Test
    fun `INV-AC-9527 matches is true only for the equal event type and an entity in scope, read for tenant A only`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val keyA = boundary.store(a, "document.rejected", "E-001")
        boundary.store(b, "document.rejected", "E-001")
        val s = Subscription(UUID.randomUUID(), "document.rejected", "secret://endpoint-1", listOf("E-001"))
        listOf(
            matches(s, "document.rejected", "E-001"),
            matches(s, "document.rejected", "E-0011"),
            matches(s, "document.rejected", "E-002"),
            matches(s, "document.acknowledged", "E-001"),
        ) shouldBe listOf(true, false, false, false)
        val read = boundary.read(a)
        read.map { it.id } shouldBe listOf(keyA)
        read.single().entityScope shouldBe listOf("E-001")
    }

    @Test
    fun `INV-AC-2038 an event of an entity outside the scope is delivered to no stored subscription`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val other = UUID.randomUUID()
        boundary.store(a, "document.acknowledged", "E")
        boundary.store(other, "document.acknowledged", "F")
        val stored = boundary.read(a)
        stored.filter { matches(it, "document.acknowledged", "F") } shouldBe emptyList()
        stored.count { matches(it, "document.acknowledged", "E") } shouldBe 1
    }

    @Test
    fun `a subscription of another tenant never matches in the session of this tenant`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        boundary.store(b, "document.held", "E-001")
        boundary.read(a).filter { matches(it, "document.held", "E-001") } shouldBe emptyList()
        boundary.read(b).count { matches(it, "document.held", "E-001") } shouldBe 1
    }

    private fun Boundary.emit(
        tenant: UUID,
        type: String,
    ) = scope.bind(TenantId(tenant), actor) {
        runner.inTransaction {
            val doc = UUID.randomUUID()
            outbox.append(
                listOf(OutboundEvent(type, "{}")),
                EventContext(
                    TenantId(tenant),
                    EntityRef("E-001"),
                    null,
                    null,
                    doc.toString(),
                    null,
                    CorrelationId("corr-1"),
                    CausationId("cause-1"),
                ),
            )
        }
    }

    private fun Boundary.outboxRows(tenant: UUID): Int =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { dsl.fetchCount(Outbox.OUTBOX) } }

    @Test
    fun `the emission guard rejects an event type not catalogued for notification and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        shouldThrow<EmissionNotPermitted> { boundary.emit(tenant, "com.dynokore.gst.notification.Invented.v1") }
        shouldThrow<EmissionNotPermitted> {
            boundary.emit(
                tenant,
                "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            )
        }
        boundary.outboxRows(tenant) shouldBe 0
    }

    @Test
    fun `a catalogued event leaves through the outbox in the command transaction`(
        @TempDir dir: Path,
    ) = run(dir) { boundary ->
        val tenant = UUID.randomUUID()
        NotificationEvents.entries.forEach { boundary.emit(tenant, it.eventType) }
        boundary.outboxRows(tenant) shouldBe NotificationEvents.entries.size
    }
}
