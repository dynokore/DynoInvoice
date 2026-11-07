package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.CounterpartyVersionRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_IDENTIFIER
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_SCHEME_STATUS
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_VERSION
import com.dynokore.gst.masterdata.domain.counterparty.ArcsDefault
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.SchemeStatus
import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import org.jooq.DSLContext
import java.time.OffsetDateTime

/** TPL-11. Reads one stored version of a counterparty back into the domain record, provenance included. */
internal class CounterpartyReader(
    private val dsl: DSLContext,
) {
    fun load(
        party: PartyRef,
        number: Long,
    ): CounterpartyVersion? {
        val row =
            dsl
                .selectFrom(COUNTERPARTY_VERSION)
                .where(COUNTERPARTY_VERSION.PARTY_REF.eq(party.value), COUNTERPARTY_VERSION.PARTY_VERSION.eq(number))
                .fetchOne() ?: return null
        val content =
            CounterpartyContent(
                identifiers = identifiersOf(party, number),
                legalName = row.legalName,
                address =
                    if (row.addressLine1 == null && row.addressLine2 == null && row.postalCode == null) {
                        null
                    } else {
                        PostalAddress(row.addressLine1, row.addressLine2, row.postalCode)
                    },
                arcs = row.arcsAgreed?.let { ArcsDefault(it, arcsProvenance(row)) },
                schemes = statusesOf(party, number),
                providedBy = ProvidedBy.valueOf(row.providedBy.column("provided_by")),
            )
        return CounterpartyVersion(party, number, row.recordedAt.column("recorded_at").toInstant(), content)
    }

    private fun arcsProvenance(row: CounterpartyVersionRecord) =
        provenance(
            row.arcsProvidedBy.column("arcs_provided_by"),
            row.arcsEvidenceRef.column("arcs_evidence_ref"),
            row.arcsCapturedAt.column("arcs_captured_at"),
        )

    private fun identifiersOf(
        party: PartyRef,
        number: Long,
    ): List<Identifier> =
        dsl
            .selectFrom(COUNTERPARTY_IDENTIFIER)
            .where(COUNTERPARTY_IDENTIFIER.PARTY_REF.eq(party.value), COUNTERPARTY_IDENTIFIER.PARTY_VERSION.eq(number))
            .orderBy(COUNTERPARTY_IDENTIFIER.CREATED_AT, COUNTERPARTY_IDENTIFIER.ID)
            .fetch()
            .map {
                Identifier(
                    IdentifierScheme.valueOf(it.scheme.column("scheme")),
                    it.identifier.column("identifier"),
                    provenance(
                        it.providedBy.column("provided_by"),
                        it.evidenceRef.column("evidence_ref"),
                        it.capturedAt.column("captured_at"),
                    ),
                )
            }

    private fun statusesOf(
        party: PartyRef,
        number: Long,
    ): List<SchemeStatus> =
        dsl
            .selectFrom(COUNTERPARTY_SCHEME_STATUS)
            .where(
                COUNTERPARTY_SCHEME_STATUS.PARTY_REF.eq(party.value),
                COUNTERPARTY_SCHEME_STATUS.PARTY_VERSION.eq(number),
            ).orderBy(COUNTERPARTY_SCHEME_STATUS.ORDINAL)
            .fetch()
            .map {
                SchemeStatus(
                    it.scheme.column("scheme"),
                    it.effectiveFrom.column("effective_from"),
                    it.effectiveTo,
                    provenance(
                        it.providedBy.column("provided_by"),
                        it.evidenceRef.column("evidence_ref"),
                        it.capturedAt.column("captured_at"),
                    ),
                )
            }

    private fun provenance(
        providedBy: String,
        evidenceRef: String,
        capturedAt: OffsetDateTime,
    ): Provenance =
        when (val by = ProvidedBy.valueOf(providedBy)) {
            ProvidedBy.MIGRATED -> Provenance.migrated(EvidenceRef(evidenceRef), capturedAt.toInstant())
            else -> Provenance.declared(by, EvidenceRef(evidenceRef), capturedAt.toInstant())
        }
}
