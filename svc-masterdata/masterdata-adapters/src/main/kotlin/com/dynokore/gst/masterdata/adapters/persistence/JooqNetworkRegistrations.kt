package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.IDENTITY_TRIPLE
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.NETWORK_REGISTRATION
import com.dynokore.gst.masterdata.application.registry.NetworkRegistrations
import com.dynokore.gst.masterdata.application.registry.OwnPeppolIds
import com.dynokore.gst.masterdata.domain.registry.NetworkRegistration
import com.dynokore.gst.masterdata.domain.registry.RegistrationState
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** TPL-11. The network-registration item of each entity of the bound tenant. */
class JooqNetworkRegistrations(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : NetworkRegistrations {
    override fun find(entity: String): NetworkRegistration? =
        dsl
            .selectFrom(NETWORK_REGISTRATION)
            .where(NETWORK_REGISTRATION.ENTITY_REF.eq(entity))
            .fetchOne()
            ?.let {
                NetworkRegistration(
                    entity,
                    RegistrationState.valueOf(it.state.column("state")),
                    it.apReference,
                    it.confirmedAt?.toInstant(),
                    it.lastCheckedAt?.toInstant(),
                )
            }

    override fun save(item: NetworkRegistration) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val confirmedAt = item.confirmedAt?.atOffset(ZoneOffset.UTC)
        val checkedAt = item.lastCheckedAt?.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(NETWORK_REGISTRATION)
            .set(NETWORK_REGISTRATION.ID, ids.next(clock.instant()))
            .set(NETWORK_REGISTRATION.ENTITY_REF, item.entity)
            .set(NETWORK_REGISTRATION.STATE, item.state.name)
            .set(NETWORK_REGISTRATION.AP_REFERENCE, item.apReference)
            .set(NETWORK_REGISTRATION.CONFIRMED_AT, confirmedAt)
            .set(NETWORK_REGISTRATION.LAST_CHECKED_AT, checkedAt)
            .set(NETWORK_REGISTRATION.CREATED_AT, now)
            .set(NETWORK_REGISTRATION.UPDATED_AT, now)
            .set(NETWORK_REGISTRATION.VERSION, 0L)
            .onConflict(NETWORK_REGISTRATION.TENANT_ID, NETWORK_REGISTRATION.ENTITY_REF)
            .doUpdate()
            .set(NETWORK_REGISTRATION.STATE, item.state.name)
            .set(NETWORK_REGISTRATION.AP_REFERENCE, item.apReference)
            .set(NETWORK_REGISTRATION.CONFIRMED_AT, confirmedAt)
            .set(NETWORK_REGISTRATION.LAST_CHECKED_AT, checkedAt)
            .set(NETWORK_REGISTRATION.UPDATED_AT, now)
            .set(NETWORK_REGISTRATION.VERSION, NETWORK_REGISTRATION.VERSION.plus(1L))
            .execute()
    }
}

/**
 * The own Peppol ID of an entity: the sender ID of the identity triple in force on the date, the one with the latest
 * effective date when several constitutions each have one; none when no triple is configured.
 */
class JooqOwnPeppolIds(
    private val dsl: DSLContext,
) : OwnPeppolIds {
    override fun of(
        entity: String,
        on: LocalDate,
    ): String? =
        dsl
            .select(IDENTITY_TRIPLE.SENDER_ID)
            .from(IDENTITY_TRIPLE)
            .where(IDENTITY_TRIPLE.ENTITY_REF.eq(entity), IDENTITY_TRIPLE.EFFECTIVE_FROM.le(on))
            .orderBy(IDENTITY_TRIPLE.EFFECTIVE_FROM.desc(), IDENTITY_TRIPLE.CONSTITUTION)
            .limit(1)
            .fetchOne()
            ?.value1()
}
