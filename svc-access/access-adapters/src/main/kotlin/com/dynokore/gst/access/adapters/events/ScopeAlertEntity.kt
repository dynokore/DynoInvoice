package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.EntityRef

/**
 * The entity an alert is filed under, read from the scope text of the alert: exactly `entity:<ref>`. Any other scope
 * (a tenant-wide one, a look-alike prefix) is refused, because the outbox refuses a domain event under the tenant-wide
 * entity (FR-K.3, INV-Q-3165).
 */
class ScopeAlertEntity : AlertEntity {
    override fun of(scope: String): EntityRef {
        require(
            scope.startsWith(PREFIX) && scope.length > PREFIX.length,
        ) { "an alert scope names an entity: entity:<ref>" }
        return EntityRef(scope.substring(PREFIX.length))
    }

    private companion object {
        const val PREFIX = "entity:"
    }
}
