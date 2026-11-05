package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.registry.ConfirmNetworkRegistration
import com.dynokore.gst.masterdata.application.registry.ReadNetworkRegistration
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestionUseCases
import com.dynokore.gst.masterdata.application.registry.SuggestionCursor
import com.dynokore.gst.masterdata.application.registry.SuggestionGstnConflict
import com.dynokore.gst.masterdata.application.registry.SuggestionNotPending
import com.dynokore.gst.masterdata.application.registry.SuggestionRefused
import com.dynokore.gst.masterdata.application.registry.SuggestionState
import com.dynokore.gst.masterdata.contract.api.NetworkRegistrationApi
import com.dynokore.gst.masterdata.contract.api.RegisterSuggestionsApi
import com.dynokore.gst.masterdata.contract.model.NetworkRegistration
import com.dynokore.gst.masterdata.contract.model.RegisterSuggestion
import com.dynokore.gst.masterdata.contract.model.RegisterSuggestionList
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestion as Suggestion
import com.dynokore.gst.masterdata.domain.registry.NetworkRegistration as Item

/**
 * TPL-08. `getNetworkRegistration` reads the stored item and makes no directory call; `confirmNetworkRegistration`
 * looks the entity's own Peppol ID up, bypassing the cache (INV-REQ-320, INV-AC-9501). The System never registers the
 * entity: no operation here writes to the directory.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class NetworkRegistrationController(
    private val read: ReadNetworkRegistration,
    private val confirm: ConfirmNetworkRegistration,
) : NetworkRegistrationApi {
    override fun getNetworkRegistration(entityRef: String): ResponseEntity<NetworkRegistration> =
        ResponseEntity.ok(view(read.handle(entity(entityRef)), null))

    override fun confirmNetworkRegistration(
        entityRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<NetworkRegistration> {
        val answer = confirm.handle(entity(entityRef))
        return ResponseEntity.ok(view(answer.item, answer.degradationCode))
    }

    private fun entity(entityRef: String): String =
        runCatching { EntityRef(entityRef).value }.getOrElse {
            throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "entityRef is invalid")
        }

    private fun view(
        item: Item,
        degradation: String?,
    ) = NetworkRegistration(
        entityRef = item.entity,
        state = NetworkRegistration.State.valueOf(item.state.name),
        apReference = item.apReference,
        confirmedAt = item.confirmedAt?.atOffset(ZoneOffset.UTC),
        lastCheckedAt = item.lastCheckedAt?.atOffset(ZoneOffset.UTC),
        degradationCode = degradation,
    )
}

/**
 * TPL-08. `listRegisterSuggestions` pages ascending by `suggestedAt` then `suggestionId` (API-18);
 * `confirmRegisterSuggestion` applies the register value with provenance, answers 404 for an unknown suggestion and
 * 409 when it is not PENDING
 * (INV-REQ-1290, INV-AC-9496).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class RegisterSuggestionsController(
    private val suggestions: RegisterSuggestionUseCases,
    private val tenants: TenantContext,
    private val cursors: SuggestionCursors,
) : RegisterSuggestionsApi {
    override fun listRegisterSuggestions(
        counterpartyRef: String?,
        state: String?,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<RegisterSuggestionList> {
        if (limit < 1 || limit > MAX_LIMIT) throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "limit is invalid")
        val party =
            counterpartyRef?.let {
                runCatching {
                    PartyRef(
                        it,
                    )
                }.getOrElse { invalid("counterpartyRef is invalid") }
            }
        val wanted =
            state?.let { s ->
                SuggestionState.entries.firstOrNull { it.name == s }
                    ?: invalid("state is invalid")
            }
        val scope =
            SuggestionCursors.Scope(
                tenants.current().toString(),
                tenants.currentActor().orEmpty(),
                counterpartyRef,
                state,
            )
        val after = cursor?.let { cursors.decode(it, scope) ?: invalid("cursor is invalid") }
        val page = suggestions.list(party, wanted, after, limit + 1)
        val shown = page.take(limit)
        val next = shown.lastOrNull()?.takeIf { page.size > limit }?.let { cursors.encode(it, scope) }
        return ResponseEntity.ok(RegisterSuggestionList(shown.map(::view), next))
    }

    override fun confirmRegisterSuggestion(
        suggestionId: String,
        idempotencyKey: UUID,
    ): ResponseEntity<RegisterSuggestion> {
        val confirmed =
            try {
                suggestions.confirm(suggestionId)
            } catch (expected: SuggestionRefused) {
                throw refusal(expected)
            } ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(view(confirmed))
    }

    private fun view(it: Suggestion) =
        RegisterSuggestion(
            suggestionId = it.suggestionRef,
            counterpartyRef = it.party.value,
            field = RegisterSuggestion.Field.valueOf(it.field.name),
            registerValue = it.registerValue,
            state = RegisterSuggestion.State.valueOf(it.state.name),
            suggestedAt = it.suggestedAt.atOffset(ZoneOffset.UTC),
            currentValue = it.currentValue,
            evidenceRef = it.evidenceRef,
        )

    /** A GSTN conflict is 409 MDM-007 (INV-Q-16979); a suggestion that is not PENDING stays 409 SRT-008. */
    private fun refusal(cause: SuggestionRefused): RuntimeException =
        when (cause) {
            is SuggestionGstnConflict -> {
                MasterdataRefusal(
                    MasterdataErrorCode.COUNTERPARTY_GSTN_CONFLICT,
                    "the counterparty holds a different GSTN (${cause.suggestionRef})",
                )
            }

            is SuggestionNotPending -> {
                ApiRefusal(ClosedErrorCode.STALE_AGGREGATE, "the suggestion is not pending (${cause.suggestionRef})")
            }
        }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)

    private companion object {
        const val MAX_LIMIT = 500
    }
}
