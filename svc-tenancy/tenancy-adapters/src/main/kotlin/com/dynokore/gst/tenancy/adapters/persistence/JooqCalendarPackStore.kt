package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoding
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.CALENDAR_PACK_ACTIVATION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.CALENDAR_PACK_VERSION
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.CalendarPackSource
import com.dynokore.gst.tenancy.application.period.StagedCalendarPack
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The calendar pack replica (jOOQ only, CS-18); forced RLS hides other tenants' rows. */
class JooqCalendarPackReplica(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : CalendarPackReplica {
    override fun stage(
        pack: StagedCalendarPack,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(CALENDAR_PACK_VERSION)
            .set(CALENDAR_PACK_VERSION.TENANT_ID, tenants.current().value)
            .set(CALENDAR_PACK_VERSION.PACK_IDENTIFIER, pack.packIdentifier)
            .set(CALENDAR_PACK_VERSION.PACK_VERSION, pack.version)
            .set(CALENDAR_PACK_VERSION.MIN_ENGINE_VERSION, pack.minEngineVersion)
            .set(CALENDAR_PACK_VERSION.PAYLOAD, pack.payloadJson)
            .set(CALENDAR_PACK_VERSION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0

    override fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(CALENDAR_PACK_ACTIVATION)
            .set(CALENDAR_PACK_ACTIVATION.TENANT_ID, tenants.current().value)
            .set(CALENDAR_PACK_ACTIVATION.PACK_IDENTIFIER, packIdentifier)
            .set(CALENDAR_PACK_ACTIVATION.PACK_VERSION, version)
            .set(CALENDAR_PACK_ACTIVATION.EFFECTIVE_FROM, effectiveFrom)
            .set(CALENDAR_PACK_ACTIVATION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0

    override fun activations(
        packIdentifier: String,
        version: String,
    ): List<LocalDate> =
        dsl
            .select(CALENDAR_PACK_ACTIVATION.EFFECTIVE_FROM)
            .from(CALENDAR_PACK_ACTIVATION)
            .where(CALENDAR_PACK_ACTIVATION.TENANT_ID.eq(tenants.current().value))
            .and(CALENDAR_PACK_ACTIVATION.PACK_IDENTIFIER.eq(packIdentifier))
            .and(CALENDAR_PACK_ACTIVATION.PACK_VERSION.eq(version))
            .fetch(CALENDAR_PACK_ACTIVATION.EFFECTIVE_FROM)
            .filterNotNull()
}

/**
 * The production binding of the port `CalendarPackSource` (INV-Q-9725 A): the pack version in force on a date is the
 * `PackRegistry.resolve` of rulepack-kernel over the activated calendar versions of the bound tenant (the most
 * recently activated version whose activation date is on or before the date), decoded by the decoder of INV-T-5055.
 * Null when none is activated for the date, or when the stored payload no longer decodes (fail closed).
 */
class JooqCalendarPackSource(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val decoder: CalendarPackDecoder,
) : CalendarPackSource {
    override fun inForce(on: LocalDate): CalendarPack? {
        val rows = activatedVersions()
        val registry = InMemoryPackRegistry()
        val payloads = HashMap<Pair<PackId, PackVersionLabel>, String>()
        val registered =
            try {
                rows.forEach { row ->
                    val manifest = row.manifest()
                    registry.register(PackVersion(manifest))
                    payloads[manifest.packId to manifest.version] =
                        row.get(CALENDAR_PACK_VERSION.PAYLOAD).column("payload")
                }
                true
            } catch (_: IllegalArgumentException) {
                false // a version label staged under two identifiers conflicts: fail closed
            }
        val resolved = if (registered) registry.resolve(PackType(CalendarPackDecoder.PACK_TYPE), null, on) else null
        val decoded = resolved?.let { decoder.decode(payloads.getValue(it.packId to it.version)) }
        return (decoded as? CalendarPackDecoding.Decoded)?.pack
    }

    private fun activatedVersions() =
        dsl
            .select(
                CALENDAR_PACK_ACTIVATION.PACK_IDENTIFIER,
                CALENDAR_PACK_ACTIVATION.PACK_VERSION,
                CALENDAR_PACK_ACTIVATION.EFFECTIVE_FROM,
                CALENDAR_PACK_VERSION.MIN_ENGINE_VERSION,
                CALENDAR_PACK_VERSION.PAYLOAD,
            ).from(CALENDAR_PACK_ACTIVATION)
            .join(CALENDAR_PACK_VERSION)
            .on(CALENDAR_PACK_VERSION.TENANT_ID.eq(CALENDAR_PACK_ACTIVATION.TENANT_ID))
            .and(CALENDAR_PACK_VERSION.PACK_IDENTIFIER.eq(CALENDAR_PACK_ACTIVATION.PACK_IDENTIFIER))
            .and(CALENDAR_PACK_VERSION.PACK_VERSION.eq(CALENDAR_PACK_ACTIVATION.PACK_VERSION))
            .where(CALENDAR_PACK_ACTIVATION.TENANT_ID.eq(tenants.current().value))
            .fetch()

    private fun Record.manifest() =
        PackManifest(
            packId = PackId(get(CALENDAR_PACK_ACTIVATION.PACK_IDENTIFIER).column("pack_identifier")),
            packType = PackType(CalendarPackDecoder.PACK_TYPE),
            version = PackVersionLabel(get(CALENDAR_PACK_ACTIVATION.PACK_VERSION).column("pack_version")),
            effectiveFrom = get(CALENDAR_PACK_ACTIVATION.EFFECTIVE_FROM).column("effective_from"),
            minEngineVersion = get(CALENDAR_PACK_VERSION.MIN_ENGINE_VERSION).column("min_engine_version"),
        )
}
