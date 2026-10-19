package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import jakarta.servlet.http.HttpServletRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** The request handling the job and policy command servlets share. */
internal object CommandHttp {
    private const val AUTHORIZATION = "Authorization"
    private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    private val KEY_INVALID = ClosedErrorCode.IDEMPOTENCY_KEY_INVALID

    fun path(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath.orEmpty())

    /** POST only (405 otherwise), then the caller's principal (503 issuer unavailable, 401 unauthenticated). */
    fun caller(
        request: HttpServletRequest,
        principals: EndUserPrincipalResolver,
        correlation: String,
    ): Step<EndUserPrincipal> =
        if (request.method != "POST") {
            Step.Stop(Problems.methodNotAllowed(correlation))
        } else {
            try {
                principals.resolve(request.getHeader(AUTHORIZATION))?.let { Step.Go(it) }
                    ?: Step.Stop(Problems.unauthenticated(correlation))
            } catch (_: TokenIssuerUnavailableException) {
                Step.Stop(Problems.closed(ClosedErrorCode.ISSUER_UNAVAILABLE, correlation))
            }
        }

    /** The body, at most [max] bytes plus one so that an oversized body is seen. */
    fun body(
        request: HttpServletRequest,
        max: Int,
    ): ByteArray = request.inputStream.readNBytes(max + 1)

    /** Runs [parse] and turns a [CommandViolation] into its problem answer. */
    inline fun <T> parsed(
        correlation: String,
        parse: () -> T,
    ): Step<T> =
        try {
            Step.Go(parse())
        } catch (violation: CommandViolation) {
            Step.Stop(Problems.closed(violation.code, correlation, violation.message.orEmpty()))
        }

    fun jsonObject(
        mapper: ObjectMapper,
        body: ByteArray,
    ): JsonNode {
        if (body.isEmpty()) CommandViolation.raise("a request body is required")
        val node =
            try {
                mapper.readTree(body)
            } catch (_: tools.jackson.core.JacksonException) {
                CommandViolation.raise("request body is not JSON")
            }
        if (node == null || !node.isObject) CommandViolation.raise("request body must be a JSON object")
        return node
    }

    fun idempotencyKey(request: HttpServletRequest): UUID {
        val text =
            request.getHeader(IDEMPOTENCY_KEY)?.trim()
                ?: CommandViolation.raise("Idempotency-Key is required", KEY_INVALID)
        return try {
            UUID.fromString(text).also {
                if (it.toString() != text.lowercase()) {
                    CommandViolation.raise("Idempotency-Key must be a UUID", KEY_INVALID)
                }
            }
        } catch (_: IllegalArgumentException) {
            CommandViolation.raise("Idempotency-Key must be a UUID", KEY_INVALID)
        }
    }

    /** API-15: the fingerprint of the method, path and body. */
    fun fingerprint(
        method: String,
        path: String,
        body: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$method $path\n".toByteArray(StandardCharsets.UTF_8))
        digest.update(body)
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    /** The answer for what [IdempotencyStore.once] did. */
    fun answer(
        outcome: IdempotencyOutcome,
        correlation: String,
    ): StoredResponse =
        when (outcome) {
            is IdempotencyOutcome.Fresh -> outcome.response
            is IdempotencyOutcome.Replay -> outcome.response
            IdempotencyOutcome.InFlight -> Problems.inFlight(correlation)
            IdempotencyOutcome.DifferentRequest -> Problems.differentRequest(correlation)
        }

    /**
     * Binds the caller's tenant and `sub` for the command (ARC-21, INV-REQ-854 b1); a tenantless caller binds the
     * platform tenant with its `sub` (INV-Q-4115).
     */
    fun <T> bound(
        scope: TenantScope,
        principal: EndUserPrincipal,
        block: () -> T,
    ): T {
        val tenant =
            principal.tenant
                ?: return checkNotNull(scope.platform) {
                    "a tenantless command needs the platform binding"
                }.bindPlatform(principal.actor, block)
        return scope.tenants.bind(tenant, principal.actor, block)
    }
}
