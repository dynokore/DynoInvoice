package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.rate.EnterRate
import com.dynokore.gst.masterdata.application.rate.EnterRateCommand
import com.dynokore.gst.masterdata.application.rate.EnterRateOutcome
import com.dynokore.gst.masterdata.contract.model.EnterRateRequest
import com.dynokore.gst.masterdata.contract.model.ExchangeRate
import com.dynokore.gst.masterdata.contract.model.RateProvenance
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.ZoneOffset

/**
 * `enterRate` (INV-T-18774, INV-REQ-506): manual entry of one missing rate with evidence. The permission
 * `exchange-rate.enter` is decided first by [EnterRate] through the replica; a denied caller gets 403 SRT-004 and
 * nothing is stored. The request has no revision: the service assigns it, and a lost race on the unique key answers
 * 409 SRT-008 with nothing stored (a retry by the client is answered 201). The quote currency is SGD (the pair is
 * written BASE/SGD, INV-T-18916).
 */
class ManualRateEntry(
    private val enter: EnterRate,
    private val tenants: TenantContext,
    private val clock: Clock,
) {
    fun handle(request: EnterRateRequest): ResponseEntity<ExchangeRate> {
        validate(request)
        val caller =
            tenants
                .currentActor()
                ?.takeIf { it.isNotBlank() && it.length <= PersonRef.MAX }
                ?.let(::PersonRef)
                ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val outcome =
            enter.handle(
                EnterRateCommand(
                    caller = caller,
                    source = request.source,
                    baseCurrency = request.currency,
                    quoteCurrency = QUOTE_CURRENCY,
                    date = request.date,
                    rate = request.rate,
                    evidenceRef = request.evidenceRef,
                ),
            )
        return answer(outcome)
    }

    /** A total `when` over [EnterRateOutcome]: a new member fails to compile. */
    private fun answer(outcome: EnterRateOutcome): ResponseEntity<ExchangeRate> =
        when (outcome) {
            is EnterRateOutcome.Stored -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ExchangeRate(
                        currency = outcome.rate.baseCurrency,
                        date = outcome.rate.date,
                        source = outcome.rate.source,
                        rate = outcome.rate.rate,
                        provenance =
                            RateProvenance(
                                kind = RateProvenance.Kind.MANUAL,
                                loadedAt = clock.instant().atOffset(ZoneOffset.UTC),
                                evidenceRef = outcome.rate.evidenceRef,
                            ),
                    ),
                )
            }

            EnterRateOutcome.EvidenceRequired -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "evidenceRef is required")
            }

            EnterRateOutcome.Forbidden -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            EnterRateOutcome.RevisionConflict -> {
                throw ApiRefusal(ClosedErrorCode.STALE_AGGREGATE)
            }
        }

    /** The shape the domain requires, refused as 400 SRT-001 before the use case runs. */
    private fun validate(request: EnterRateRequest) {
        val invalid =
            when {
                !CURRENCY.matches(request.currency) -> "currency"
                request.source.isBlank() -> "source"
                request.rate.signum() <= 0 -> "rate"
                else -> null
            }
        if (invalid != null) throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, invalid)
    }

    private companion object {
        const val QUOTE_CURRENCY = "SGD"
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
