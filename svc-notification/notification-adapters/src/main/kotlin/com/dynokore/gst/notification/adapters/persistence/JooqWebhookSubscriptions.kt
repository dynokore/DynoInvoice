package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.WebhookSubscription.Companion.WEBHOOK_SUBSCRIPTION
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.WebhookSubscriptionScope.Companion.WEBHOOK_SUBSCRIPTION_SCOPE
import com.dynokore.gst.notification.application.WebhookSubscriptions
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import com.dynokore.gst.notification.application.WebhookSubscription as Subscription

/** Reads the subscriptions through jOOQ; forced row level security limits the read to the bound tenant. */
@Component
class JooqWebhookSubscriptions(
    private val dsl: DSLContext,
) : WebhookSubscriptions {
    override fun ofBoundTenant(): List<Subscription> =
        dsl
            .select(
                WEBHOOK_SUBSCRIPTION.ID,
                WEBHOOK_SUBSCRIPTION.EVENT_TYPE,
                WEBHOOK_SUBSCRIPTION.ENDPOINT_REF,
                WEBHOOK_SUBSCRIPTION_SCOPE.ENTITY_REF,
            ).from(WEBHOOK_SUBSCRIPTION)
            .leftJoin(WEBHOOK_SUBSCRIPTION_SCOPE)
            .on(WEBHOOK_SUBSCRIPTION_SCOPE.SUBSCRIPTION_ID.eq(WEBHOOK_SUBSCRIPTION.ID))
            .orderBy(WEBHOOK_SUBSCRIPTION.ID, WEBHOOK_SUBSCRIPTION_SCOPE.ENTITY_REF)
            .fetch()
            .groupBy { checkNotNull(it.value1()) }
            .map { (id, rows) ->
                val first = rows.first()
                Subscription(
                    id,
                    checkNotNull(first.value2()),
                    checkNotNull(first.value3()),
                    rows.mapNotNull { it.value4() },
                )
            }
}
