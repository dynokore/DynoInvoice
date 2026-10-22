package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.PartyAnswer
import com.dynokore.gst.masterdata.application.counterparty.PartyQuestion
import com.dynokore.gst.masterdata.application.counterparty.ReadCounterpartyHistory
import com.dynokore.gst.masterdata.application.counterparty.ResolveCounterparty
import com.dynokore.gst.masterdata.application.identity.ResolveCommand
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.contract.api.ResolutionApi
import com.dynokore.gst.masterdata.contract.model.CounterpartyHistory
import com.dynokore.gst.masterdata.contract.model.CounterpartyVersion
import com.dynokore.gst.masterdata.contract.model.IdentityTripleFacts
import com.dynokore.gst.masterdata.contract.model.ResolutionResult
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.Resolution
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * TPL-08. `resolveCounterparty`: the identity triple in force for (entity, constitution) on the transaction date, or
 * 422 MDM-005 when none is configured (ruling INV-Q-7886 A); and, where a party is named by one of its identifiers, the
 * counterparty with its findings (a finding never changes the 200: INV-Q-8421 A), `sameTenant`, `sameGroup`, the ARCS
 * default and the approved scheme status in force on the transaction date. `getCounterpartyHistory`: the versions of
 * one counterparty in write order (INV-Q-8425 A).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class ResolutionController(
    private val resolve: ResolveIdentityTriple,
    private val resolveParty: ResolveCounterparty,
    private val history: ReadCounterpartyHistory,
) : ResolutionApi {
    override fun resolveCounterparty(
        entityRef: String,
        constitution: String,
        transactionDate: LocalDate,
        partyScheme: String?,
        partyValue: String?,
    ): ResponseEntity<ResolutionResult> {
        val entity = runCatching { EntityRef(entityRef) }.getOrElse { invalid("entityRef is invalid") }
        val legal = runCatching { Constitution(constitution) }.getOrElse { invalid("constitution is invalid") }
        if ((partyScheme == null) != (partyValue == null)) invalid("partyScheme and partyValue go together")
        return when (val resolution = resolve.handle(ResolveCommand(entity, legal, transactionDate))) {
            is Resolution.Resolved -> {
                val triple = resolution.version.triple
                val facts = IdentityTripleFacts(triple.senderId, triple.supplierId, triple.supplierGstn)
                ResponseEntity.ok(withParty(facts, entityRef, partyScheme, partyValue, transactionDate))
            }

            is Resolution.Blocked -> {
                throw MasterdataRefusal(
                    MasterdataErrorCode.IDENTITY_TRIPLE_NOT_CONFIGURED,
                    "No identity triple is configured for constitution ${legal.code} of entity ${entity.value}",
                )
            }
        }
    }

    private fun withParty(
        facts: IdentityTripleFacts,
        entityRef: String,
        partyScheme: String?,
        partyValue: String?,
        transactionDate: LocalDate,
    ): ResolutionResult {
        if (partyScheme == null || partyValue == null) return ResolutionResult(facts)
        // a scheme this service does not hold is a party it cannot find: unresolved (MDM-001), not a refusal
        val scheme = IdentifierScheme.entries.firstOrNull { it.name == partyScheme }
        val answer =
            if (scheme == null) {
                PartyAnswer.Unresolved()
            } else {
                resolveParty.handle(PartyQuestion(entityRef, scheme, partyValue, transactionDate))
            }
        val findings = answer.findings.map(CounterpartyMapping::finding).ifEmpty { null }
        return when (answer) {
            is PartyAnswer.Unresolved -> {
                ResolutionResult(facts, findings = findings)
            }

            is PartyAnswer.Resolved -> {
                val counterparty =
                    CounterpartyMapping.counterparty(
                        answer.version,
                        answer.schemesInForce,
                        answer.sameness,
                    )
                ResolutionResult(
                    identityTriple = facts,
                    counterparty = counterparty,
                    sameGroup = answer.sameness.sameGroup,
                    sameTenant = answer.sameness.sameTenant,
                    arcsGstChargeAgreed = counterparty.arcsGstChargeAgreed,
                    approvedSchemes = counterparty.approvedSchemes,
                    findings = findings,
                )
            }
        }
    }

    override fun getCounterpartyHistory(
        counterpartyRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<CounterpartyHistory> {
        val party = runCatching { PartyRef(counterpartyRef) }.getOrElse { throw ApiRefusal(ClosedErrorCode.NOT_FOUND) }
        val after =
            if (cursor ==
                null
            ) {
                0L
            } else {
                cursor.toLongOrNull()?.takeIf { it >= 0 } ?: invalid("cursor is invalid")
            }
        if (limit < 1) invalid("limit is invalid")
        val page = history.handle(party, after, limit) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(
            CounterpartyHistory(
                items =
                    page.versions.map {
                        CounterpartyVersion(
                            version = it.version.toInt(),
                            recordedAt = it.recordedAt.atOffset(ZoneOffset.UTC),
                            current = it.version == page.current,
                            counterparty = CounterpartyMapping.counterparty(it, it.content.schemes),
                        )
                    },
                nextCursor = page.nextAfter?.toString(),
            ),
        )
    }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)
}
