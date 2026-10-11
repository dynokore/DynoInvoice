package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * What the command knows about the events it emits (TPL-03 `EventContext`; FR-K.3).
 *
 * A tenantless platform event (INV-Q-2927 option D) is written under `TenantId.PLATFORM` with
 * [EntityRef.PLATFORM]; the two are always paired, so the platform tenant never carries a real entity
 * and a real tenant never carries the platform entity.
 *
 * @property aggregateKey the key events are ordered under; becomes the CloudEvents `subject`
 *   (the Doc UUID text where the event concerns a document, FR-AH.10: [docUuid], when given,
 *   must equal it, because the CloudEvents contract has no separate Doc UUID attribute).
 * @property aggregateVersion the aggregate's version after the change, or `null`. The outbox
 *   keeps its own per-key counter, so `docsequence` is monotonic whatever the caller passes;
 *   a version ahead of the counter is honoured (TPL-05), a lower one never lowers it.
 */
data class EventContext(
    val tenant: TenantId,
    val entity: EntityRef,
    val docUuid: DocUuid?,
    val transactionDate: LocalDate?,
    val aggregateKey: String,
    val aggregateVersion: Long?,
    val correlation: CorrelationId,
    val causation: CausationId,
) {
    init {
        require(aggregateKey.isNotBlank()) { "aggregate key must not be blank" }
        require(
            docUuid == null || aggregateKey == docUuid.toString(),
        ) { "a document-scoped event is keyed by its Doc UUID" }
        require(aggregateVersion == null || aggregateVersion >= 1) { "aggregate version starts at 1" }
        require(pairingHolds(tenant, entity)) { PLATFORM_PAIR }
    }
}
