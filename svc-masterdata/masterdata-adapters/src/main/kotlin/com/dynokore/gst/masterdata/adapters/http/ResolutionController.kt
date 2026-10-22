package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.PartyAnswer
import com.dynokore.gst.masterdata.application.counterparty.PartyKey
import com.dynokore.gst.masterdata.application.counterparty.PartyQuestion
import com.dynokore.gst.masterdata.application.counterparty.ReadCounterpartyHistory
import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.counterparty.ResolveCounterparty
import com.dynokore.gst.masterdata.application.identity.ResolveCommand
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.contract.api.ResolutionApi
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.CounterpartyHistory
import com.dynokore.gst.masterdata.contract.model.CounterpartyVersion
import com.dynokore.gst.masterdata.contract.model.IdentityTripleFacts
import com.dynokore.gst.masterdata.contract.model.IngestedCounterpartyRequest
import com.dynokore.gst.masterdata.contract.model.ResolutionResult
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
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
import java.util.UUID

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
    private val record: RecordCounterparty,
) : ResolutionApi {
    override fun resolveCounterparty(
        entityRef: String,
        constitution: String,
        transactionDate: LocalDate,
        partyRef: String?,
        partyScheme: String?,
        partyValue: String?,
    ): ResponseEntity<ResolutionResult> {
        val entity = runCatching { EntityRef(entityRef) }.getOrElse { invalid("entityRef is invalid") }
        val legal = runCatching { Constitution(constitution) }.getOrElse { invalid("constitution is invalid") }
        // exactly one of partyRef or the pair names the party; none is the identity triple only (INV-Q-8484 A)
        if ((partyScheme == null) != (partyValue == null)) invalid("partyScheme and partyValue go together")
        if (partyRef != null && partyScheme != null) invalid("partyRef and partyScheme/partyValue exclude each other")
        return when (val resolution = resolve.handle(ResolveCommand(entity, legal, transactionDate))) {
            is Resolution.Resolved -> {
                val triple = resolution.version.triple
                val facts = IdentityTripleFacts(triple.senderId, triple.supplierId, triple.supplierGstn)
                ResponseEntity.ok(
                    withParty(facts, entityRef, NamedParty(partyRef, partyScheme, partyValue), transactionDate),
                )
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
        named: NamedParty,
        transactionDate: LocalDate,
    ): ResolutionResult {
        val answer =
            answerFor(entityRef, named, transactionDate) ?: return ResolutionResult(facts)
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

    /** The query parameters that name a party: a reference, or a scheme and value pair. */
    private data class NamedParty(
        val partyRef: String?,
        val partyScheme: String?,
        val partyValue: String?,
    )

    /** The answer for the party the query names; null when it names none. */
    private fun answerFor(
        entityRef: String,
        named: NamedParty,
        transactionDate: LocalDate,
    ): PartyAnswer? {
        val (partyRef, partyScheme, partyValue) = named
        val byRef =
            partyRef?.let { PartyKey.ByRef(runCatching { PartyRef(it) }.getOrElse { invalid("partyRef is invalid") }) }
        if (byRef == null && (partyScheme == null || partyValue == null)) return null
        // a scheme this service does not hold is a party it cannot find: unresolved (MDM-001), not a refusal
        val scheme = IdentifierScheme.entries.firstOrNull { it.name == partyScheme }
        val key = byRef ?: scheme?.let { PartyKey.ByIdentifier(it, checkNotNull(partyValue)) }
        return key?.let { resolveParty.handle(PartyQuestion(entityRef, it, transactionDate)) }
            ?: PartyAnswer.Unresolved()
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

    /**
     * The write path of the ingestion counterparty object (INV-Q-8486 A): stores what canonical-svc supplies as a new
     * version and never validates it as a precondition (the `must_not` of this service). `MIGRATED` is not a member of
     * the request enum, so the contract refuses it with 400 before this runs.
     */
    override fun recordIngestedCounterparty(
        counterpartyRef: String,
        idempotencyKey: UUID,
        ingestedCounterpartyRequest: IngestedCounterpartyRequest,
    ): ResponseEntity<Counterparty> {
        val request = ingestedCounterpartyRequest
        val party = runCatching { PartyRef(counterpartyRef) }.getOrElse { invalid("counterpartyRef is invalid") }
        val entity = request.entityRef.trim().takeIf { it.isNotEmpty() } ?: invalid("entityRef must not be blank")
        val origin =
            when (request.providedBy) {
                IngestedCounterpartyRequest.ProvidedBy.COUNTERPARTY -> WriteOrigin.COUNTERPARTY_LINK
                IngestedCounterpartyRequest.ProvidedBy.INTERNAL -> WriteOrigin.OPERATOR
            }
        val change =
            CounterpartyChangeMapping.change(
                request.resolvedIdentifiers,
                request.legalName,
                request.address,
                request.arcsGstChargeAgreed,
                request.approvedSchemes,
            )
        val version =
            record.handle(
                RecordCommand(entity, party, change, CounterpartyChangeMapping.evidence(request.evidenceRef), origin),
            )
        return ResponseEntity.ok(CounterpartyMapping.counterparty(version, version.content.schemes))
    }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)
}
