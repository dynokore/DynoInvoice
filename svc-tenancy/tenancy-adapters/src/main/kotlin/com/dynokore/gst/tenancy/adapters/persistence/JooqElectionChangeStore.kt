package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.records.ElectionChangeRecord
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ELECTION_CHANGE
import com.dynokore.gst.tenancy.application.election.ApprovedChange
import com.dynokore.gst.tenancy.application.election.ClosedChange
import com.dynokore.gst.tenancy.application.election.ElectionChangeStore
import com.dynokore.gst.tenancy.application.election.PendingChange
import com.dynokore.gst.tenancy.application.election.PendingChangePage
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** The pending changes of the elections (jOOQ only, CS-18); forced RLS hides the rows of other tenants. */
class JooqElectionChangeStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ElectionChangeStore {
    @Suppress("LongParameterList") // one request with its requester
    override fun request(
        entityRef: String,
        kind: ElectionKind,
        value: String,
        basis: String?,
        effectiveFrom: LocalDate,
        requestedBy: String,
    ): PendingChange? {
        val changeRef =
            "CHG-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(REF_DIGITS)
                    .uppercase()
        val requestedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val stored =
            dsl
                .insertInto(ELECTION_CHANGE)
                .set(ELECTION_CHANGE.TENANT_ID, tenants.current().value)
                .set(ELECTION_CHANGE.CHANGE_REF, changeRef)
                .set(ELECTION_CHANGE.ENTITY_REF, entityRef)
                .set(ELECTION_CHANGE.VARIATION_POINT, kind.variationPoint)
                .set(ELECTION_CHANGE.REQUESTED_VALUE, value)
                .set(ELECTION_CHANGE.REQUESTED_BASIS, basis)
                .set(ELECTION_CHANGE.EFFECTIVE_FROM, effectiveFrom)
                .set(ELECTION_CHANGE.REQUESTED_BY, requestedBy)
                .set(ELECTION_CHANGE.REQUESTED_AT, requestedAt.atOffset(ZoneOffset.UTC))
                .set(ELECTION_CHANGE.STATE, PENDING)
                .onConflict(
                    ELECTION_CHANGE.TENANT_ID,
                    ELECTION_CHANGE.ENTITY_REF,
                    ELECTION_CHANGE.VARIATION_POINT,
                ).where(ELECTION_CHANGE.STATE.eq(PENDING))
                .doNothing()
                .execute()
        if (stored == 0) return null
        return PendingChange(changeRef, entityRef, kind, value, basis, effectiveFrom, requestedBy, requestedAt)
    }

    /** Locks the row (FOR UPDATE) so two approvals of one change run one after the other. */
    override fun pending(
        entityRef: String,
        changeRef: String,
    ): PendingChange? =
        dsl
            .selectFrom(ELECTION_CHANGE)
            .where(ELECTION_CHANGE.TENANT_ID.eq(tenants.current().value))
            .and(ELECTION_CHANGE.ENTITY_REF.eq(entityRef))
            .and(ELECTION_CHANGE.CHANGE_REF.eq(changeRef))
            .and(ELECTION_CHANGE.STATE.eq(PENDING))
            .forUpdate()
            .fetchOne()
            ?.let(::change)

    override fun approve(
        entityRef: String,
        changeRef: String,
        approvedBy: String,
    ): ApprovedChange? {
        val approvedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        return dsl
            .update(ELECTION_CHANGE)
            .set(ELECTION_CHANGE.STATE, APPROVED)
            .set(ELECTION_CHANGE.APPROVED_BY, approvedBy)
            .set(ELECTION_CHANGE.APPROVED_AT, approvedAt.atOffset(ZoneOffset.UTC))
            .where(ELECTION_CHANGE.TENANT_ID.eq(tenants.current().value))
            .and(ELECTION_CHANGE.ENTITY_REF.eq(entityRef))
            .and(ELECTION_CHANGE.CHANGE_REF.eq(changeRef))
            .and(ELECTION_CHANGE.STATE.eq(PENDING))
            .returning()
            .fetchOne()
            ?.let { ApprovedChange(change(it), approvedBy, approvedAt) }
    }

    override fun withdraw(
        entityRef: String,
        changeRef: String,
        closedBy: String,
    ): ClosedChange? = close(entityRef, changeRef, WITHDRAWN, closedBy)

    override fun decline(
        entityRef: String,
        changeRef: String,
        closedBy: String,
    ): ClosedChange? = close(entityRef, changeRef, DECLINED, closedBy)

    private fun close(
        entityRef: String,
        changeRef: String,
        state: String,
        closedBy: String,
    ): ClosedChange? {
        val closedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        return dsl
            .update(ELECTION_CHANGE)
            .set(ELECTION_CHANGE.STATE, state)
            .set(ELECTION_CHANGE.CLOSED_BY, closedBy)
            .set(ELECTION_CHANGE.CLOSED_AT, closedAt.atOffset(ZoneOffset.UTC))
            .where(ELECTION_CHANGE.TENANT_ID.eq(tenants.current().value))
            .and(ELECTION_CHANGE.ENTITY_REF.eq(entityRef))
            .and(ELECTION_CHANGE.CHANGE_REF.eq(changeRef))
            .and(ELECTION_CHANGE.STATE.eq(PENDING))
            .returning()
            .fetchOne()
            ?.let { ClosedChange(change(it), closedBy, closedAt) }
    }

    /** Keyset page over (requested_at, change_ref); the token is the last item's position, URL-safe base64. */
    override fun list(
        entityRef: String,
        limit: Int,
        after: String?,
    ): PendingChangePage {
        val position = after?.let(::decode)
        val rows =
            dsl
                .selectFrom(ELECTION_CHANGE)
                .where(ELECTION_CHANGE.TENANT_ID.eq(tenants.current().value))
                .and(ELECTION_CHANGE.ENTITY_REF.eq(entityRef))
                .and(ELECTION_CHANGE.STATE.eq(PENDING))
                .let { query ->
                    if (position == null) {
                        query
                    } else {
                        query.and(
                            DSL.row(ELECTION_CHANGE.REQUESTED_AT, ELECTION_CHANGE.CHANGE_REF).gt(
                                DSL.row(position.first.atOffset(ZoneOffset.UTC), position.second),
                            ),
                        )
                    }
                }.orderBy(ELECTION_CHANGE.REQUESTED_AT, ELECTION_CHANGE.CHANGE_REF)
                .limit(limit + 1)
                .fetch()
                .map(::change)
        val items = rows.take(limit)
        val next = if (rows.size > limit) encode(items.last()) else null
        return PendingChangePage(items, next)
    }

    private fun encode(last: PendingChange): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString("${last.requestedAt}|${last.changeRef}".toByteArray(Charsets.UTF_8))

    private fun decode(token: String): Pair<Instant, String> {
        val text =
            try {
                String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("not a page token", e)
            }
        val parts = text.split('|')
        require(parts.size == 2 && parts[1].isNotEmpty()) { "not a page token" }
        val at =
            try {
                Instant.parse(parts[0])
            } catch (e: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException("not a page token", e)
            }
        return at to parts[1]
    }

    private fun change(row: ElectionChangeRecord): PendingChange =
        PendingChange(
            row.changeRef.column("change_ref"),
            row.entityRef.column("entity_ref"),
            ElectionKind.entries.single { it.variationPoint == row.variationPoint.column("variation_point") },
            row.requestedValue.column("requested_value"),
            row.requestedBasis,
            row.effectiveFrom.column("effective_from"),
            row.requestedBy.column("requested_by"),
            row.requestedAt.column("requested_at").toInstant(),
        )

    private companion object {
        const val PENDING = "PENDING"
        const val APPROVED = "APPROVED"
        const val WITHDRAWN = "WITHDRAWN"
        const val DECLINED = "DECLINED"
        const val REF_DIGITS = 12
    }
}
