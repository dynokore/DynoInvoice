package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.CounterpartyVersionRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_IDENTIFIER
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_SCHEME_STATUS
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_VERSION
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.domain.counterparty.ArcsDefault
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.SchemeStatus
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TPL-11. The DSLContext is service-runtime's, wired with the tenancy-jooq listeners, which add `tenant_id = <bound
 * tenant>` to every statement below or refuse it; forced row-level security stays behind that (INV-REQ-866).
 *
 * A write locks the head row of the counterparty with one upsert that takes the next version number, so concurrent
 * writers of one counterparty each get a number and both versions are kept (INV-REQ-2576).
 */
class JooqCounterpartyRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : CounterpartyRepository {
    private val reader = CounterpartyReader(dsl)

    override fun findByIdentifier(
        scheme: IdentifierScheme,
        value: String,
    ): CounterpartyVersion? {
        val match =
            dsl
                .select(COUNTERPARTY.PARTY_REF, COUNTERPARTY.LAST_VERSION)
                .from(COUNTERPARTY)
                .join(COUNTERPARTY_IDENTIFIER)
                .on(
                    COUNTERPARTY_IDENTIFIER.PARTY_REF.eq(COUNTERPARTY.PARTY_REF),
                    COUNTERPARTY_IDENTIFIER.PARTY_VERSION.eq(COUNTERPARTY.LAST_VERSION),
                ).where(COUNTERPARTY_IDENTIFIER.SCHEME.eq(scheme.name), COUNTERPARTY_IDENTIFIER.IDENTIFIER.eq(value))
                .orderBy(COUNTERPARTY.PARTY_REF)
                .limit(1)
                .fetchOne() ?: return null
        return reader.load(PartyRef(match.value1().column("party_ref")), match.value2().column("last_version"))
    }

    override fun current(party: PartyRef): CounterpartyVersion? {
        val last =
            dsl
                .select(COUNTERPARTY.LAST_VERSION)
                .from(COUNTERPARTY)
                .where(COUNTERPARTY.PARTY_REF.eq(party.value))
                .fetchOne()
                ?.value1() ?: return null
        return reader.load(party, last)
    }

    override fun append(
        party: PartyRef,
        content: CounterpartyContent,
        recordedAt: Instant,
    ): CounterpartyVersion {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val number =
            dsl
                .insertInto(COUNTERPARTY)
                .set(COUNTERPARTY.ID, ids.next(clock.instant()))
                .set(COUNTERPARTY.PARTY_REF, party.value)
                .set(COUNTERPARTY.LAST_VERSION, 1L)
                .set(COUNTERPARTY.CREATED_AT, now)
                .set(COUNTERPARTY.UPDATED_AT, now)
                .set(COUNTERPARTY.VERSION, 0L)
                .onConflict(COUNTERPARTY.TENANT_ID, COUNTERPARTY.PARTY_REF)
                .doUpdate()
                .set(COUNTERPARTY.LAST_VERSION, COUNTERPARTY.LAST_VERSION.plus(1L))
                .set(COUNTERPARTY.UPDATED_AT, now)
                .set(COUNTERPARTY.VERSION, COUNTERPARTY.VERSION.plus(1L))
                .returning(COUNTERPARTY.LAST_VERSION)
                .fetchOne()
                ?.lastVersion
                .column("last_version")
        insertVersion(party, number, content, recordedAt, now)
        return CounterpartyVersion(party, number, recordedAt, content)
    }

    override fun history(
        party: PartyRef,
        afterVersion: Long,
        limit: Int,
    ): List<CounterpartyVersion> =
        dsl
            .select(COUNTERPARTY_VERSION.PARTY_VERSION)
            .from(COUNTERPARTY_VERSION)
            .where(COUNTERPARTY_VERSION.PARTY_REF.eq(party.value), COUNTERPARTY_VERSION.PARTY_VERSION.gt(afterVersion))
            .orderBy(COUNTERPARTY_VERSION.PARTY_VERSION)
            .limit(limit)
            .fetch()
            .mapNotNull { reader.load(party, it.value1().column("party_version")) }

    private fun insertVersion(
        party: PartyRef,
        number: Long,
        content: CounterpartyContent,
        recordedAt: Instant,
        now: OffsetDateTime,
    ) {
        val arcs = content.arcs
        dsl
            .insertInto(COUNTERPARTY_VERSION)
            .set(COUNTERPARTY_VERSION.ID, ids.next(clock.instant()))
            .set(COUNTERPARTY_VERSION.PARTY_REF, party.value)
            .set(COUNTERPARTY_VERSION.PARTY_VERSION, number)
            .set(COUNTERPARTY_VERSION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .set(COUNTERPARTY_VERSION.LEGAL_NAME, content.legalName)
            .set(COUNTERPARTY_VERSION.ADDRESS_LINE1, content.address?.line1)
            .set(COUNTERPARTY_VERSION.ADDRESS_LINE2, content.address?.line2)
            .set(COUNTERPARTY_VERSION.POSTAL_CODE, content.address?.postalCode)
            .set(COUNTERPARTY_VERSION.PROVIDED_BY, content.providedBy.name)
            .set(COUNTERPARTY_VERSION.ARCS_AGREED, arcs?.agreed)
            .set(COUNTERPARTY_VERSION.ARCS_PROVIDED_BY, arcs?.provenance?.providedBy?.name)
            .set(COUNTERPARTY_VERSION.ARCS_EVIDENCE_REF, arcs?.provenance?.evidenceRef?.value)
            .set(COUNTERPARTY_VERSION.ARCS_CAPTURED_AT, arcs?.provenance?.capturedAt?.atOffset(ZoneOffset.UTC))
            .set(COUNTERPARTY_VERSION.CREATED_AT, now)
            .set(COUNTERPARTY_VERSION.UPDATED_AT, now)
            .set(COUNTERPARTY_VERSION.VERSION, 0L)
            .execute()
        content.identifiers.forEach { insertIdentifier(party, number, it, now) }
        content.schemes.forEachIndexed { ordinal, status -> insertStatus(party, number, ordinal, status, now) }
    }

    private fun insertIdentifier(
        party: PartyRef,
        number: Long,
        identifier: Identifier,
        now: OffsetDateTime,
    ) {
        dsl
            .insertInto(COUNTERPARTY_IDENTIFIER)
            .set(COUNTERPARTY_IDENTIFIER.ID, ids.next(clock.instant()))
            .set(COUNTERPARTY_IDENTIFIER.PARTY_REF, party.value)
            .set(COUNTERPARTY_IDENTIFIER.PARTY_VERSION, number)
            .set(COUNTERPARTY_IDENTIFIER.SCHEME, identifier.scheme.name)
            .set(COUNTERPARTY_IDENTIFIER.IDENTIFIER, identifier.value)
            .set(COUNTERPARTY_IDENTIFIER.PROVIDED_BY, identifier.provenance.providedBy.name)
            .set(COUNTERPARTY_IDENTIFIER.EVIDENCE_REF, identifier.provenance.evidenceRef.value)
            .set(COUNTERPARTY_IDENTIFIER.CAPTURED_AT, identifier.provenance.capturedAt.atOffset(ZoneOffset.UTC))
            .set(COUNTERPARTY_IDENTIFIER.CREATED_AT, now)
            .set(COUNTERPARTY_IDENTIFIER.UPDATED_AT, now)
            .set(COUNTERPARTY_IDENTIFIER.VERSION, 0L)
            .execute()
    }

    private fun insertStatus(
        party: PartyRef,
        number: Long,
        ordinal: Int,
        status: SchemeStatus,
        now: OffsetDateTime,
    ) {
        dsl
            .insertInto(COUNTERPARTY_SCHEME_STATUS)
            .set(COUNTERPARTY_SCHEME_STATUS.ID, ids.next(clock.instant()))
            .set(COUNTERPARTY_SCHEME_STATUS.PARTY_REF, party.value)
            .set(COUNTERPARTY_SCHEME_STATUS.PARTY_VERSION, number)
            .set(COUNTERPARTY_SCHEME_STATUS.ORDINAL, ordinal)
            .set(COUNTERPARTY_SCHEME_STATUS.SCHEME, status.scheme)
            .set(COUNTERPARTY_SCHEME_STATUS.EFFECTIVE_FROM, status.effectiveFrom)
            .set(COUNTERPARTY_SCHEME_STATUS.EFFECTIVE_TO, status.effectiveTo)
            .set(COUNTERPARTY_SCHEME_STATUS.PROVIDED_BY, status.provenance.providedBy.name)
            .set(COUNTERPARTY_SCHEME_STATUS.EVIDENCE_REF, status.provenance.evidenceRef.value)
            .set(COUNTERPARTY_SCHEME_STATUS.CAPTURED_AT, status.provenance.capturedAt.atOffset(ZoneOffset.UTC))
            .set(COUNTERPARTY_SCHEME_STATUS.CREATED_AT, now)
            .set(COUNTERPARTY_SCHEME_STATUS.UPDATED_AT, now)
            .set(COUNTERPARTY_SCHEME_STATUS.VERSION, 0L)
            .execute()
    }
}
