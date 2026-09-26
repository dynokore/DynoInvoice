package com.dynokore.platform.evidencestore

import java.time.Instant

/**
 * A hold placed on one (tenant, entity, period)'s retention clock (FR-D.4):
 * while held, [RetentionGate.canDelete] is always `false`, regardless of how
 * long retention has already run.
 */
data class LegalHold(val reason: String, val placedAt: Instant) {
    init {
        require(reason.isNotBlank()) { "a legal hold must record a reason" }
    }
}
