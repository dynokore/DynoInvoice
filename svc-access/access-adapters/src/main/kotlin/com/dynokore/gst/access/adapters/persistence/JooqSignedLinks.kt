package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.SIGNED_LINK
import com.dynokore.gst.access.application.link.LinkTenants
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRecipient
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.LinkSource
import com.dynokore.gst.access.domain.link.LinkStatus
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.persistence.StaleAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The listeners of the DSLContext add the bound tenant to every statement; row-level security stays behind. */
class JooqSignedLinks(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    /** The sender recorded on a link issued by an event (ruling INV-Q-17207 A): the system actor, never a role. */
    private val systemActor: String,
    private val ids: UuidV7 = UuidV7(),
) : SignedLinks {
    override fun find(ref: LinkRef): SignedLink? =
        dsl
            .selectFrom(SIGNED_LINK)
            .where(SIGNED_LINK.LINK_REF.eq(ref.value))
            .fetchOne()
            ?.let { toLink(it) }

    override fun lock(ref: LinkRef): SignedLink? =
        dsl
            .selectFrom(SIGNED_LINK)
            .where(SIGNED_LINK.LINK_REF.eq(ref.value))
            .forUpdate()
            .fetchOne()
            ?.let { toLink(it) }

    override fun findBySourceEvent(eventId: String): SignedLink? =
        dsl
            .selectFrom(SIGNED_LINK)
            .where(SIGNED_LINK.SOURCE_EVENT_ID.eq(eventId))
            .fetchOne()
            ?.let { toLink(it) }

    override fun insert(link: SignedLink) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val sender = if (link.source == LinkSource.EVENT) systemActor else actor()
        dsl
            .insertInto(SIGNED_LINK)
            .set(SIGNED_LINK.ID, ids.next(clock.instant()))
            .set(SIGNED_LINK.LINK_REF, link.ref.value)
            .set(SIGNED_LINK.PURPOSE, link.purpose)
            .set(SIGNED_LINK.CLIENT_REF, link.client?.value)
            .set(SIGNED_LINK.CONSENT_REF, link.consent?.value)
            .set(SIGNED_LINK.ALERT_ENTITY, link.alertEntity.value)
            .set(SIGNED_LINK.SUBJECT_REFERENCE, link.subject)
            .set(SIGNED_LINK.RECIPIENT_EMAIL, link.recipient.email)
            .set(SIGNED_LINK.RECIPIENT_MOBILE, link.recipient.mobile)
            .set(SIGNED_LINK.EXPIRES_AT, link.expiresAt.atOffset(ZoneOffset.UTC))
            .set(SIGNED_LINK.PASSCODE_DIGEST, link.passcodeDigest)
            .set(SIGNED_LINK.STATUS, link.status.name)
            .set(SIGNED_LINK.WRONG_ENTRIES, link.wrongEntries)
            .set(SIGNED_LINK.VERIFIED_AT, link.verifiedAt?.atOffset(ZoneOffset.UTC))
            .set(SIGNED_LINK.INVALIDATED_AT, link.invalidatedAt?.atOffset(ZoneOffset.UTC))
            .set(SIGNED_LINK.SOURCE, link.source.name)
            .set(SIGNED_LINK.SOURCE_EVENT_ID, link.sourceEventId)
            .set(SIGNED_LINK.CREATED_AT, now)
            .set(SIGNED_LINK.CREATED_BY, sender)
            .set(SIGNED_LINK.UPDATED_AT, now)
            .set(SIGNED_LINK.UPDATED_BY, sender)
            .set(SIGNED_LINK.VERSION, link.version)
            .execute()
    }

    override fun update(
        before: SignedLink,
        after: SignedLink,
    ) {
        val updated =
            dsl
                .update(SIGNED_LINK)
                .set(SIGNED_LINK.STATUS, after.status.name)
                .set(SIGNED_LINK.WRONG_ENTRIES, after.wrongEntries)
                .set(SIGNED_LINK.VERIFIED_AT, after.verifiedAt?.atOffset(ZoneOffset.UTC))
                .set(SIGNED_LINK.INVALIDATED_AT, after.invalidatedAt?.atOffset(ZoneOffset.UTC))
                .set(SIGNED_LINK.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(SIGNED_LINK.UPDATED_BY, actor())
                .set(SIGNED_LINK.VERSION, before.version + 1)
                .where(SIGNED_LINK.LINK_REF.eq(before.ref.value), SIGNED_LINK.VERSION.eq(before.version))
                .execute()
        if (updated != 1) throw StaleAggregate("SignedLink", before.version + 1)
    }

    private fun toLink(row: Record): SignedLink =
        SignedLink(
            ref = LinkRef(row.get(SIGNED_LINK.LINK_REF).column("link_ref")),
            purpose = row.get(SIGNED_LINK.PURPOSE).column("purpose"),
            client = row.get(SIGNED_LINK.CLIENT_REF)?.let { EntityRef(it) },
            consent = row.get(SIGNED_LINK.CONSENT_REF)?.let { ConsentRef(it) },
            alertEntity = EntityRef(row.get(SIGNED_LINK.ALERT_ENTITY).column("alert_entity")),
            subject = row.get(SIGNED_LINK.SUBJECT_REFERENCE),
            recipient =
                LinkRecipient(
                    row.get(SIGNED_LINK.RECIPIENT_EMAIL).column("recipient_email"),
                    row.get(SIGNED_LINK.RECIPIENT_MOBILE),
                ),
            expiresAt = row.get(SIGNED_LINK.EXPIRES_AT).column("expires_at").toInstant(),
            passcodeDigest = row.get(SIGNED_LINK.PASSCODE_DIGEST).column("passcode_digest"),
            status = LinkStatus.valueOf(row.get(SIGNED_LINK.STATUS).column("status")),
            wrongEntries = row.get(SIGNED_LINK.WRONG_ENTRIES).column("wrong_entries"),
            verifiedAt = row.get(SIGNED_LINK.VERIFIED_AT)?.toInstant(),
            invalidatedAt = row.get(SIGNED_LINK.INVALIDATED_AT)?.toInstant(),
            source = LinkSource.valueOf(row.get(SIGNED_LINK.SOURCE).column("source")),
            sourceEventId = row.get(SIGNED_LINK.SOURCE_EVENT_ID),
            version = row.get(SIGNED_LINK.VERSION).column("version"),
        )

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
}

/**
 * TPL-11. Calls `access.resolve_signed_link_tenant(link_ref)` (owned by the NOLOGIN `access_sys`, executable by
 * `access_runtime` only) in a read transaction, under the platform binding the caller holds. The table is never read
 * with row-level security off.
 */
class JooqLinkTenants(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
) : LinkTenants {
    override fun tenantOf(ref: LinkRef): TenantId? =
        tx.inTransaction {
            val resolve =
                DSL.function(DSL.name("access", FUNCTION), SQLDataType.UUID, DSL.`val`(ref.value, SQLDataType.CLOB))
            dsl
                .select(resolve)
                .fetchOne()
                ?.get(resolve)
                ?.let { TenantId(it) }
        }

    companion object {
        const val FUNCTION = "resolve_signed_link_tenant"
    }
}
