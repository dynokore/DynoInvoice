package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.AdapterNotRegistered
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.DuplicateEntityRef
import com.dynokore.gst.transport.application.EntityAssignedToOtherConnection
import com.dynokore.gst.transport.application.ObserveGuardRefused
import com.dynokore.gst.transport.application.RotationRefusal
import com.dynokore.gst.transport.domain.InvalidAcknowledgementRoute
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

/**
 * The refusals of the administration commands, scoped to [AdministrationController] (API-20 envelope through
 * [ApiRefusalAdvice]).
 * - 400 SRT-001: a body that does not parse or breaks the request schema (a fourth route value, a repeated entity
 *   ref); the text offered is never echoed (API-22).
 * - 404 SRT-005: a connection ref naming no record of the tenant, or a failover target that is no registered adapter.
 * - 422 SUB-018: an entity held by another connection.
 * - 422 SUB-023 or SUB-019: a clearing or removal the Observe guard refuses, with a reason naming the connection and
 *   its entities.
 *
 * Nothing is recorded or published in any of them: the use cases raise before they write.
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

    /**
     * A rotation the use case refuses (INV-T-22003 declares the codes): an unset window fails closed as an internal
     * error with no detail (a configuration fault, not the caller's, API-22); a reference the vault does not hold is
     * 422 SUB-026; a reference that is already current is 422 SUB-027; a window still open is 409 SUB-028. Nothing is
     * changed in any refusal.
     */
    @ExceptionHandler(CredentialRotationRefused::class)
    fun rotationRefused(refused: CredentialRotationRefused): ResponseEntity<String> =
        when (refused.reason) {
            RotationRefusal.NO_WINDOW -> {
                refusals.refuse(ApiRefusal(ClosedErrorCode.INTERNAL_ERROR))
            }

            RotationRefusal.UNKNOWN_REFERENCE -> {
                refusals.refuse(CatalogueRefusal(HTTP_UNPROCESSABLE, UNKNOWN_REFERENCE, UNKNOWN_REFERENCE_TITLE))
            }

            RotationRefusal.SAME_AS_CURRENT -> {
                refusals.refuse(CatalogueRefusal(HTTP_UNPROCESSABLE, SAME_AS_CURRENT, SAME_AS_CURRENT_TITLE))
            }

            RotationRefusal.ROTATION_IN_PROGRESS -> {
                refusals.refuse(CatalogueRefusal(HTTP_CONFLICT, ROTATION_IN_PROGRESS, ROTATION_IN_PROGRESS_TITLE))
            }
        }

    @ExceptionHandler(ObserveGuardRefused::class)
    fun observeGuard(refused: ObserveGuardRefused): ResponseEntity<String> =
        refusals.refuse(CatalogueRefusal(HTTP_UNPROCESSABLE, refused.code, title(refused.code), refused.message))

    /** SUB-018: an entity is held by at most one connection of its tenant; the reason names what the caller owns. */
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
        const val HTTP_CONFLICT = 409
        const val UNKNOWN_REFERENCE = "SUB-026"
        const val UNKNOWN_REFERENCE_TITLE = "The replacement credential reference is not held by the secret store"
        const val SAME_AS_CURRENT = "SUB-027"
        const val SAME_AS_CURRENT_TITLE = "The replacement credential reference is already the current one"
        const val ROTATION_IN_PROGRESS = "SUB-028"
        const val ROTATION_IN_PROGRESS_TITLE = "The overlap window of an earlier rotation has not ended"
        const val ASSIGNED_ELSEWHERE = "SUB-018"
        const val ASSIGNED_TITLE = "An entity is already assigned to another connection of the tenant"
        const val CLEARING_REFUSED = "SUB-023"
        const val CLEARING_TITLE = "Acknowledgement route cannot be cleared while an entity is in Observe mode"
        const val REMOVAL_TITLE = "An entity in Observe mode cannot be removed from the connection"
    }
}

/** A rotation refused by the use case, carried to [AdministrationAdvice]. */
class CredentialRotationRefused(
    val reason: RotationRefusal,
) : RuntimeException("rotation refused: ${reason.name}")
