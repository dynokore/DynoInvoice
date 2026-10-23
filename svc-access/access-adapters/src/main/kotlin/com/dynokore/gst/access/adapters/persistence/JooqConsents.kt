package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.CONSENT
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.persistence.StaleAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. Every read goes to the table: no consent is kept between calls, so a withdrawal shows at the next check
 * (INV-REQ-865). The listeners of the DSLContext add the bound tenant to every statement; row-level security stays
 * behind.
 */
class JooqConsents(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ConsentRepository {
    override fun find(ref: ConsentRef): Consent? =
        dsl
            .selectFrom(CONSENT)
            .where(CONSENT.CONSENT_REF.eq(ref.value))
            .fetchOne()
            ?.let { toConsent(it) }

    override fun lock(ref: ConsentRef): Consent? =
        dsl
            .selectFrom(CONSENT)
            .where(CONSENT.CONSENT_REF.eq(ref.value))
            .forUpdate()
            .fetchOne()
            ?.let { toConsent(it) }

    override fun findLiveForClient(client: EntityRef): Consent? =
        dsl
            .selectFrom(CONSENT)
            .where(CONSENT.CLIENT_ENTITY_REF.eq(client.value), CONSENT.STATUS.eq(ConsentStatus.LIVE.name))
            .fetchOne()
            ?.let { toConsent(it) }

    override fun insert(consent: Consent) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = actor()
        dsl
            .insertInto(CONSENT)
            .set(CONSENT.ID, ids.next(clock.instant()))
            .set(CONSENT.CONSENT_REF, consent.ref.value)
            .set(CONSENT.CLIENT_ENTITY_REF, consent.client.value)
            .set(CONSENT.AGENT_REF, consent.agent.value)
            .set(CONSENT.SUBMIT_INVOICE_DATA, ScopeItem.SUBMIT_INVOICE_DATA in consent.items)
            .set(CONSENT.FILE_GST_RETURN, ScopeItem.FILE_GST_RETURN in consent.items)
            .set(CONSENT.MAPPING_APPROVAL, ScopeItem.MAPPING_APPROVAL in consent.items)
            .set(CONSENT.VALID_FROM, consent.terms.validFrom)
            .set(CONSENT.AUTHORISING_PERSON_EMAIL, consent.terms.authorisingPersonEmail)
            .set(CONSENT.STATUS, consent.status.name)
            .set(CONSENT.HANDOVER_STATEMENT_REF, consent.handoverStatementRef)
            .set(CONSENT.CREATED_AT, now)
            .set(CONSENT.CREATED_BY, actor)
            .set(CONSENT.UPDATED_AT, now)
            .set(CONSENT.UPDATED_BY, actor)
            .set(CONSENT.VERSION, consent.version)
            .execute()
    }

    override fun update(
        before: Consent,
        after: Consent,
    ) {
        val updated =
            dsl
                .update(CONSENT)
                .set(CONSENT.STATUS, after.status.name)
                .set(CONSENT.HANDOVER_STATEMENT_REF, after.handoverStatementRef)
                .set(CONSENT.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(CONSENT.UPDATED_BY, actor())
                .set(CONSENT.VERSION, after.version)
                .where(CONSENT.CONSENT_REF.eq(before.ref.value), CONSENT.VERSION.eq(before.version))
                .execute()
        if (updated != 1) throw StaleAggregate("Consent", after.version)
    }

    override fun nextAuthorityVersion(): Long = dsl.nextval(AUTHORITY_VERSION_SEQ)

    override fun nextRef(): ConsentRef =
        ConsentRef(
            "CNS-" +
                ids
                    .next(clock.instant())
                    .toString()
                    .replace("-", "")
                    .takeLast(REF_DIGITS),
        )

    private fun toConsent(row: Record): Consent =
        Consent(
            ref = ConsentRef(row.get(CONSENT.CONSENT_REF).column("consent_ref")),
            client = EntityRef(row.get(CONSENT.CLIENT_ENTITY_REF).column("client_entity_ref")),
            agent = IdentityRef(row.get(CONSENT.AGENT_REF).column("agent_ref")),
            terms =
                ConsentTerms(
                    items =
                        buildSet {
                            if (row.get(CONSENT.SUBMIT_INVOICE_DATA) == true) add(ScopeItem.SUBMIT_INVOICE_DATA)
                            if (row.get(CONSENT.FILE_GST_RETURN) == true) add(ScopeItem.FILE_GST_RETURN)
                            if (row.get(CONSENT.MAPPING_APPROVAL) == true) add(ScopeItem.MAPPING_APPROVAL)
                        },
                    validFrom = row.get(CONSENT.VALID_FROM).column("valid_from"),
                    authorisingPersonEmail =
                        row.get(CONSENT.AUTHORISING_PERSON_EMAIL).column("authorising_person_email"),
                ),
            status = ConsentStatus.valueOf(row.get(CONSENT.STATUS).column("status")),
            handoverStatementRef = row.get(CONSENT.HANDOVER_STATEMENT_REF),
            version = row.get(CONSENT.VERSION).column("version"),
        )

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a consent change runs in a bound transaction" }

    private companion object {
        const val REF_DIGITS = 8
    }
}
