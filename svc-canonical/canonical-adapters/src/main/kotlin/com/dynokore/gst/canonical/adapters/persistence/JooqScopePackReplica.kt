package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SCOPE_PACK_ACTIVATION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SCOPE_PACK_FUNCTION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SCOPE_PACK_VERSION
import com.dynokore.gst.canonical.application.sample.SampleDocumentFunctions
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One (document type code, function) pair of the classification table of a staged scope pack. */
data class ScopePackRow(
    val documentTypeCode: Int,
    val documentFunction: UpstreamFunction,
)

/** A staged scope pack version: its identity and the distinct (type code, function) pairs of its coded rows. */
data class StagedScopePack(
    val packIdentifier: String,
    val version: String,
    val rows: Set<ScopePackRow>,
)

/** The activation of a pack version from a date. */
data class ScopePackActivation(
    val packIdentifier: String,
    val version: String,
    val effectiveFrom: LocalDate,
)

/**
 * TPL-11. jOOQ only. The scope pack versions canonical-svc received by event (INV-REQ-772; ruling of INV-T-25433:
 * event-carried state transfer). The tables are platform reference data (no tenant). [functionOf] answers from the
 * version in force: the activation with the greatest `effective_from` not after the UTC date of [clock] among the
 * activations whose version has a staged row; two activated versions on that date, or none, answer null.
 */
class JooqScopePackReplica(
    private val dsl: DSLContext,
    private val clock: Clock,
) : SampleDocumentFunctions {
    /** True when the version was new and stored with its rows; false (nothing changed) when it was already held. */
    fun stage(
        pack: StagedScopePack,
        recordedAt: Instant = clock.instant(),
    ): Boolean {
        val inserted =
            dsl
                .insertInto(SCOPE_PACK_VERSION)
                .set(SCOPE_PACK_VERSION.PACK_IDENTIFIER, pack.packIdentifier)
                .set(SCOPE_PACK_VERSION.PACK_VERSION, pack.version)
                .set(SCOPE_PACK_VERSION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute()
        if (inserted == 0) return false
        pack.rows.forEach { row ->
            dsl
                .insertInto(SCOPE_PACK_FUNCTION)
                .set(SCOPE_PACK_FUNCTION.PACK_IDENTIFIER, pack.packIdentifier)
                .set(SCOPE_PACK_FUNCTION.PACK_VERSION, pack.version)
                .set(SCOPE_PACK_FUNCTION.DOCUMENT_TYPE_CODE, row.documentTypeCode.toShort())
                .set(SCOPE_PACK_FUNCTION.DOCUMENT_FUNCTION, row.documentFunction.name)
                .execute()
        }
        return true
    }

    /** True when the activation was new; false (nothing changed) when the pair was already activated. */
    fun activate(
        activation: ScopePackActivation,
        recordedAt: Instant = clock.instant(),
    ): Boolean =
        dsl
            .insertInto(SCOPE_PACK_ACTIVATION)
            .set(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER, activation.packIdentifier)
            .set(SCOPE_PACK_ACTIVATION.PACK_VERSION, activation.version)
            .set(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM, activation.effectiveFrom)
            .set(SCOPE_PACK_ACTIVATION.RECORDED_AT, recordedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0

    override fun functionOf(documentTypeCode: Int): UpstreamFunction? {
        val inForce = versionInForce() ?: return null
        val functions =
            dsl
                .selectDistinct(SCOPE_PACK_FUNCTION.DOCUMENT_FUNCTION)
                .from(SCOPE_PACK_FUNCTION)
                .where(SCOPE_PACK_FUNCTION.PACK_IDENTIFIER.eq(inForce.first))
                .and(SCOPE_PACK_FUNCTION.PACK_VERSION.eq(inForce.second))
                .and(SCOPE_PACK_FUNCTION.DOCUMENT_TYPE_CODE.eq(documentTypeCode.toShort()))
                .fetch(SCOPE_PACK_FUNCTION.DOCUMENT_FUNCTION)
        return functions.singleOrNull()?.let { UpstreamFunction.valueOf(it) }
    }

    /** (identifier, version) of the one activation on the greatest date not after today among staged versions. */
    private fun versionInForce(): Pair<String, String>? {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val staged =
            dsl
                .select(
                    SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER,
                    SCOPE_PACK_ACTIVATION.PACK_VERSION,
                    SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM,
                ).from(SCOPE_PACK_ACTIVATION)
                .join(SCOPE_PACK_VERSION)
                .on(SCOPE_PACK_VERSION.PACK_IDENTIFIER.eq(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER))
                .and(SCOPE_PACK_VERSION.PACK_VERSION.eq(SCOPE_PACK_ACTIVATION.PACK_VERSION))
                .where(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM.le(today))
                .fetch()
        val greatest = staged.maxOfOrNull { checkNotNull(it.get(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM)) } ?: return null
        return staged
            .filter { it.get(SCOPE_PACK_ACTIVATION.EFFECTIVE_FROM) == greatest }
            .map {
                checkNotNull(it.get(SCOPE_PACK_ACTIVATION.PACK_IDENTIFIER)) to
                    checkNotNull(it.get(SCOPE_PACK_ACTIVATION.PACK_VERSION))
            }.singleOrNull()
    }
}
