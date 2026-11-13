package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.AdapterNotRegistered
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.DuplicateEntityRef
import com.dynokore.gst.transport.application.EntityAssignedToOtherConnection
import com.dynokore.gst.transport.application.ObserveGuardRefused
import com.dynokore.gst.transport.domain.InvalidAcknowledgementRoute
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

/**
 * The refusals of the route commands, scoped to [AdministrationController] (API-20 envelope through
 * [ApiRefusalAdvice]). A body that does not parse or breaks the request schema, a fourth route value among them, is
 * 400 SRT-001 (the text offered is never echoed, API-22); a connection ref naming no record of the tenant, or a failover target that is no registered adapter, is 404
 * SRT-005; an entity held by another connection is 422 SUB-018; a repeated entity ref is 400 SRT-001; a clearing or removal the Observe guard refuses is 422 SUB-023 or SUB-019 with a reason naming the
 * connection and its entities. Nothing is recorded or published in any of them: the use cases raise before they write.
 */
@RestControllerAdvice(assignableTypes = [AdministrationController::class])
class AdministrationAdvice(
    private val refusals: ApiRefusalAdvice,
) {
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        InvalidAcknowledgementRoute::class,
        DuplicateEntityRef::class,
    )
    fun contractViolation(): ResponseEntity<String> = refusals.refuse(ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION))

    @ExceptionHandler(ConnectionNotFound::class, AdapterNotRegistered::class)
    fun notFound(): ResponseEntity<String> = refusals.refuse(ApiRefusal(ClosedErrorCode.NOT_FOUND))

    @ExceptionHandler(ObserveGuardRefused::class)
    fun observeGuard(refused: ObserveGuardRefused): ResponseEntity<String> =
        refusals.refuse(CatalogueRefusal(HTTP_UNPROCESSABLE, refused.code, title(refused.code), refused.message))

    /** SUB-018: an entity is assigned to at most one connection of its tenant; the reason names what the caller owns. */
    @ExceptionHandler(EntityAssignedToOtherConnection::class)
    fun assignedElsewhere(refused: EntityAssignedToOtherConnection): ResponseEntity<String> =
        refusals.refuse(
            CatalogueRefusal(
                HTTP_UNPROCESSABLE,
                ASSIGNED_ELSEWHERE,
                ASSIGNED_TITLE,
                "entity ${refused.entity.value} is already assigned to connection ${refused.otherConnection}",
            ),
        )

    private fun title(code: String) = if (code == CLEARING_REFUSED) CLEARING_TITLE else REMOVAL_TITLE

    private companion object {
        const val HTTP_UNPROCESSABLE = 422
        const val ASSIGNED_ELSEWHERE = "SUB-018"
        const val ASSIGNED_TITLE = "An entity is already assigned to another connection of the tenant"
        const val CLEARING_REFUSED = "SUB-023"
        const val CLEARING_TITLE = "Acknowledgement route cannot be cleared while an entity is in Observe mode"
        const val REMOVAL_TITLE = "An entity in Observe mode cannot be removed from the connection"
    }
}
