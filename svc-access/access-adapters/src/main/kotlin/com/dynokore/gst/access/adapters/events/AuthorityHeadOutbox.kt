package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.productRuntimeAuthorityHeadEvents.adapters.events.model.AuthorityHeadObservedPayload
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-05: appends the platform event AuthorityHeadObserved (INV-Q-17070 B, INV-REQ-1201) through outbox-and-queue. The
 * heartbeat carries the value of the head record `access.authority_head` (INV-Q-17072 A): the highest authorityVersion
 * among the AuthorityChanged events committed, never the sequence value last drawn and never a read of the purgeable
 * outbox. Nothing is appended while the record holds no row.
 *
 * Each publish is one transaction bound to the platform tenant (`PlatformTenantBinding.bindPlatform`); it first takes
 * the transaction-level advisory lock [AUTHORITY_HEAD_PUBLISH_LOCK] with `pg_try_advisory_xact_lock` and appends only
 * when the lock is granted, so two instances publishing at once write one event, and the lock is released at commit
 * and at rollback. This guards concurrent publishes only; it does not claim one row per interval across sequential
 * ticks of two instances. The type is the platform type of the catalogue, under `TenantId.PLATFORM` and
 * `EntityRef.PLATFORM` (the guard of outbox-and-queue is not changed).
 */
@Suppress("LongParameterList") // one collaborator per port of the publish transaction
class AuthorityHeadOutbox(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val dsl: DSLContext,
    private val head: AuthorityHeadStore,
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val incarnations: AuthorityIncarnationStore,
) {
    /** One heartbeat; true when an event was appended, false when the lock was held elsewhere or no head exists. */
    fun publish(): Boolean =
        platform.bindPlatform(ACTOR) {
            tx.inTransaction {
                val lock = DSL.function(DSL.name("pg_try_advisory_xact_lock"), SQLDataType.BOOLEAN, DSL.inline(KEY))
                if (dsl.select(lock).fetchOne()?.value1() != true) {
                    false
                } else {
                    head.current()?.let { append(it) } != null
                }
            }
        }

    private fun append(version: Long) {
        val context = EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, AGGREGATE_KEY)
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(version).toString())), context)
    }

    internal fun payload(version: Long) =
        mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(
            AuthorityHeadObservedPayload(
                authorityVersion = Math.toIntExact(version),
                incarnation = Math.toIntExact(incarnations.current()),
                observedAt = clock.instant().atOffset(ZoneOffset.UTC),
            ),
        )

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "accessAuthorityHeadObserved"

        /** The workload principal of the heartbeat (ARC-21). */
        const val ACTOR = "svc:access-svc/job:authority-head-heartbeat"

        val EVENT_TYPE: String = PlatformEventCatalogue.AUTHORITY_HEAD_OBSERVED.eventType
        private const val AGGREGATE_KEY = "authority-head"

        /** The one lock key of the publish; derived from its own name, shared with no other lock of this service. */
        val AUTHORITY_HEAD_PUBLISH_LOCK: Long =
            ByteBuffer
                .wrap(
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest("access.authority-head-publish".toByteArray(StandardCharsets.UTF_8)),
                ).long
        private val KEY get() = AUTHORITY_HEAD_PUBLISH_LOCK
    }
}
