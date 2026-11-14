package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_CURRENCY_STATE
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.PackCurrencyStore
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.StoredCurrency
import com.dynokore.gst.rules.domain.pack.PackCurrencyState
import com.dynokore.gst.rules.domain.pack.UnactivatedPack
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.LocalDate
import java.util.UUID

/**
 * The DEP-001 state of the installation in `rules.pack_currency_state` (one row under the platform tenant) and the
 * published tuple. An evaluation that publishes writes the tuple and the outbox row in one transaction.
 */
class JooqPackCurrencyStore(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val events: PackEventEmitter,
) : PackCurrencyStore {
    private val owner = TenantId.PLATFORM.value

    override fun load(): StoredCurrency? =
        inTx {
            dsl
                .selectFrom(PACK_CURRENCY_STATE)
                .where(PACK_CURRENCY_STATE.TENANT_ID.eq(owner))
                .fetchOne()
                ?.let { row ->
                    StoredCurrency(
                        lastReachedOn = checkNotNull(row.lastReachedOn),
                        published =
                            if (row.published == true) {
                                PackCurrencyState(
                                    depCode = com.dynokore.gst.rules.domain.pack.PackCurrency.DEP_CODE,
                                    raised = row.raised == true,
                                    packIdentifier = row.packIdentifier,
                                    packVersion = row.packVersion,
                                    daysToEffectiveDate = row.daysToEffectiveDate,
                                    daysSinceLastReached = row.daysSinceLastReached,
                                )
                            } else {
                                null
                            },
                        evaluatedOn = row.evaluatedOn,
                    )
                }
        }

    override fun unactivated(): UnactivatedPack? =
        inTx {
            dsl
                .select(PACK_REGISTRY.PACK_ID, PACK_REGISTRY.PACK_VERSION, PACK_REGISTRY.EFFECTIVE_FROM)
                .from(PACK_REGISTRY)
                .where(PACK_REGISTRY.TENANT_ID.eq(owner))
                .and(PACK_REGISTRY.STATUS.`in`("STAGED", "APPROVED", "ACKNOWLEDGED"))
                .orderBy(PACK_REGISTRY.EFFECTIVE_FROM.asc(), PACK_REGISTRY.PACK_VERSION.asc())
                .limit(1)
                .fetchOne()
                ?.let {
                    UnactivatedPack(
                        it[PACK_REGISTRY.PACK_ID]!!,
                        it[PACK_REGISTRY.PACK_VERSION]!!,
                        it[PACK_REGISTRY.EFFECTIVE_FROM]!!,
                    )
                }
        }

    override fun markReached(day: LocalDate) {
        inTx {
            dsl
                .insertInto(PACK_CURRENCY_STATE)
                .set(PACK_CURRENCY_STATE.TENANT_ID, owner)
                .set(PACK_CURRENCY_STATE.LAST_REACHED_ON, day)
                .onConflict(PACK_CURRENCY_STATE.TENANT_ID)
                .doUpdate()
                .set(PACK_CURRENCY_STATE.LAST_REACHED_ON, day)
                .execute()
        }
    }

    override fun record(
        day: LocalDate,
        state: PackCurrencyState,
        publish: Boolean,
    ) {
        inTx {
            if (publish) {
                dsl
                    .update(PACK_CURRENCY_STATE)
                    .set(PACK_CURRENCY_STATE.PUBLISHED, true)
                    .set(PACK_CURRENCY_STATE.RAISED, state.raised)
                    .set(PACK_CURRENCY_STATE.PACK_IDENTIFIER, state.packIdentifier)
                    .set(PACK_CURRENCY_STATE.PACK_VERSION, state.packVersion)
                    .set(PACK_CURRENCY_STATE.DAYS_TO_EFFECTIVE_DATE, state.daysToEffectiveDate)
                    .set(PACK_CURRENCY_STATE.DAYS_SINCE_LAST_REACHED, state.daysSinceLastReached)
                    .set(PACK_CURRENCY_STATE.EVALUATED_ON, day)
                    .where(PACK_CURRENCY_STATE.TENANT_ID.eq(owner))
                    .execute()
                events.emit(listOf(event(state)), context(day))
            } else {
                dsl
                    .update(PACK_CURRENCY_STATE)
                    .set(PACK_CURRENCY_STATE.EVALUATED_ON, day)
                    .where(PACK_CURRENCY_STATE.TENANT_ID.eq(owner))
                    .execute()
            }
        }
    }

    private fun event(state: PackCurrencyState) =
        PackEvent.PackCurrencyChanged(
            depCode = state.depCode,
            raised = state.raised,
            packIdentifier = state.packIdentifier,
            packVersion = state.packVersion,
            daysToEffectiveDate = state.daysToEffectiveDate,
            daysSinceLastReached = state.daysSinceLastReached,
        )

    private fun context(day: LocalDate): EventContext {
        val id = UUID.randomUUID().toString()
        return EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef(JooqPackImportStore.PLATFORM_ENTITY),
            docUuid = null,
            transactionDate = null,
            aggregateKey = "pack-currency",
            aggregateVersion = null,
            correlation = CorrelationId(id),
            causation = CausationId(id),
        )
    }

    private fun <T> inTx(block: () -> T): T = platform.bindPlatform(ACTOR) { transactions.inTransaction { block() } }

    private companion object {
        const val ACTOR = "svc:rules-svc/op:pack-currency"
    }
}
