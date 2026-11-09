package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.rate.GetRegisterAndRates
import com.dynokore.gst.masterdata.application.rate.RateLookup
import com.dynokore.gst.masterdata.application.rate.RegisterAndRatesQuestion
import com.dynokore.gst.masterdata.application.registry.RegisterQuestion
import com.dynokore.gst.masterdata.contract.api.RegisterAndRatesApi
import com.dynokore.gst.masterdata.contract.model.ExchangeRate
import com.dynokore.gst.masterdata.contract.model.RateProvenance
import com.dynokore.gst.masterdata.contract.model.RegisterAndRateResult
import com.dynokore.gst.masterdata.contract.model.RegisterAndRateResultRegister
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `getRegisterAndRates` (INV-T-18775, INV-REQ-506): a service-to-service lookup on the sync edge, like
 * `resolveCounterparty`. It decides no person permission (ruling of INV-T-18775, NIST SP 800-53 AC-6): no
 * `AuthorityReplica` is asked and no role is read. The entity is the query parameter `entityRef`, required and not
 * blank (else 400 SRT-001, before anything is asked). The elected source is that entity's.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller
open class RegisterAndRatesController(
    private val lookup: GetRegisterAndRates,
    private val clock: Clock,
) : RegisterAndRatesApi {
    override fun getRegisterAndRates(
        counterpartyUen: String,
        currency: String,
        transactionDate: LocalDate,
        entityRef: String?,
    ): ResponseEntity<RegisterAndRateResult> {
        val entity =
            entityRef?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "entityRef is required")
        val answer =
            lookup.handle(
                RegisterAndRatesQuestion(RegisterQuestion(entity, counterpartyUen), currency, transactionDate),
            )
        val register =
            RegisterAndRateResultRegister(
                RegisterAndRateResultRegister.Status.forValue(answer.register.status),
                answer.register.asOf,
            )
        val rate = (answer.rate as? RateLookup.Found)?.rate
        return ResponseEntity.ok(RegisterAndRateResult(register, rate?.let { rate(it) }))
    }

    private fun rate(found: com.dynokore.gst.masterdata.domain.rate.ExchangeRate) =
        ExchangeRate(
            currency = found.baseCurrency,
            date = found.date,
            source = found.source,
            rate = found.rate,
            provenance =
                RateProvenance(
                    kind =
                        when (found.provenance) {
                            Provenance.MANUAL_ENTRY -> RateProvenance.Kind.MANUAL
                            Provenance.IMPORT -> RateProvenance.Kind.IMPORT
                        },
                    loadedAt = clock.instant().atOffset(ZoneOffset.UTC),
                    importId = found.importId,
                    evidenceRef = found.evidenceRef,
                ),
        )
}
