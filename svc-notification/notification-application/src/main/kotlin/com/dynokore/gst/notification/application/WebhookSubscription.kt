package com.dynokore.gst.notification.application

import java.util.UUID

/**
 * A tenant's webhook subscription (INV-REQ-820): one event type, an endpoint named by a reference into the secret
 * store, and the entities it is scoped to. The event type is a value of the contract, not a constant of the service.
 */
data class WebhookSubscription(
    val id: UUID,
    val eventType: String,
    val endpointRef: String,
    val entityScope: List<String>,
)

/**
 * The subscriptions of the tenant the current session is bound to (CS-19). The port reads what the database lets the
 * session see; it never filters by tenant itself.
 */
interface WebhookSubscriptions {
    fun ofBoundTenant(): List<WebhookSubscription>
}

/**
 * INV-AC-9527: true exactly when the event type equals the subscription's and the entity reference is in its scope
 * list. Both comparisons are equality, never a prefix or a case-insensitive match.
 */
fun matches(
    subscription: WebhookSubscription,
    eventType: String,
    entityRef: String,
): Boolean = subscription.eventType == eventType && entityRef in subscription.entityScope
