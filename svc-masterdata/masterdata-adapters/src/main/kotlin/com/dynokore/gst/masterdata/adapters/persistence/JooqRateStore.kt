package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.EXCHANGE_RATE
import com.dynokore.gst.masterdata.application.rate.RateStore
import com.dynokore.gst.masterdata.application.rate.SaveResult
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * TPL-11. The rate records of the bound tenant. A record is inserted once and never updated: the table grants the
 * runtime login no UPDATE. The unique key is (tenant, source, base currency, quote currency, rate date, revision); a
 * save of a stored key is answered [SaveResult.RevisionConflict] and changes nothing, whether the key was stored
 * before the call (`ON CONFLICT DO NOTHING` affects no row) or a racing writer took it first (SQLSTATE 23505).
 */
class JooqRateStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : RateStore {
    override fun latest(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        date: LocalDate,
    ): ExchangeRate? =
        dsl
            .selectFrom(EXCHANGE_RATE)
            .where(pair(source, baseCurrency, quoteCurrency), EXCHANGE_RATE.RATE_DATE.eq(date))
            .orderBy(EXCHANGE_RATE.REVISION.desc())
            .limit(1)
            .fetchOne()
            ?.let(::rate)

    override fun history(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        date: LocalDate,
    ): List<ExchangeRate> =
        dsl
            .selectFrom(EXCHANGE_RATE)
            .where(pair(source, baseCurrency, quoteCurrency), EXCHANGE_RATE.RATE_DATE.eq(date))
            .orderBy(EXCHANGE_RATE.REVISION.asc())
            .fetch()
            .map(::rate)

    override fun inForce(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        onDate: LocalDate,
    ): ExchangeRate? =
        dsl
            .selectFrom(EXCHANGE_RATE)
            .where(pair(source, baseCurrency, quoteCurrency), EXCHANGE_RATE.RATE_DATE.le(onDate))
            .orderBy(EXCHANGE_RATE.RATE_DATE.desc(), EXCHANGE_RATE.REVISION.desc())
            .limit(1)
            .fetchOne()
            ?.let(::rate)

    override fun save(rate: ExchangeRate): SaveResult =
        try {
            val inserted =
                dsl
                    .insertInto(EXCHANGE_RATE)
                    .set(EXCHANGE_RATE.ID, ids.next(clock.instant()))
                    .set(EXCHANGE_RATE.SOURCE, rate.source)
                    .set(EXCHANGE_RATE.BASE_CURRENCY, rate.baseCurrency)
                    .set(EXCHANGE_RATE.QUOTE_CURRENCY, rate.quoteCurrency)
                    .set(EXCHANGE_RATE.RATE_DATE, rate.date)
                    .set(EXCHANGE_RATE.RATE, rate.rate)
                    .set(EXCHANGE_RATE.PROVENANCE, rate.provenance.name)
                    .set(EXCHANGE_RATE.EVIDENCE_REF, rate.evidenceRef)
                    .set(EXCHANGE_RATE.IMPORT_ID, rate.importId)
                    .set(EXCHANGE_RATE.REVISION, rate.revision)
                    .set(EXCHANGE_RATE.LOADED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                    .onConflictOnConstraint(DSL.constraint(DSL.name(UNIQUE_KEY)))
                    .doNothing()
                    .execute()
            if (inserted == 1) SaveResult.Saved else SaveResult.RevisionConflict
        } catch (failure: DataAccessException) {
            if (uniqueViolation(failure)) SaveResult.RevisionConflict else throw failure
        }

    private fun pair(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
    ) = EXCHANGE_RATE.SOURCE
        .eq(source)
        .and(EXCHANGE_RATE.BASE_CURRENCY.eq(baseCurrency))
        .and(EXCHANGE_RATE.QUOTE_CURRENCY.eq(quoteCurrency))

    private fun uniqueViolation(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (current is SQLException && current.sqlState == UNIQUE_VIOLATION) return true
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return false
    }

    private fun rate(row: org.jooq.Record): ExchangeRate =
        ExchangeRate(
            source = row.get(EXCHANGE_RATE.SOURCE).column("source"),
            baseCurrency = row.get(EXCHANGE_RATE.BASE_CURRENCY).column("base_currency"),
            quoteCurrency = row.get(EXCHANGE_RATE.QUOTE_CURRENCY).column("quote_currency"),
            date = row.get(EXCHANGE_RATE.RATE_DATE).column("rate_date"),
            rate = row.get(EXCHANGE_RATE.RATE).column("rate"),
            provenance = Provenance.valueOf(row.get(EXCHANGE_RATE.PROVENANCE).column("provenance")),
            evidenceRef = row.get(EXCHANGE_RATE.EVIDENCE_REF),
            revision = row.get(EXCHANGE_RATE.REVISION).column("revision"),
            importId = row.get(EXCHANGE_RATE.IMPORT_ID),
        )

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val MAX_DEPTH = 16
        const val UNIQUE_KEY = "exchange_rate_key"
    }
}
