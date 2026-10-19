package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.RelaxationId
import com.dynokore.platform.configresolver.RelaxationStatus
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.SettingSource
import com.dynokore.platform.configresolver.SettingVersion
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VariationPointRetired
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * setPolicy and acknowledgeControlRelaxation over HTTP (INV-T-5180; coordinator ruling INV-Q-5172; platform-contracts
 * `service-runtime/openapi.yaml`), onto the service's config-resolver [PolicyWriter]:
 * `POST /internal/v1/policies/{variationPoint}` and `POST /internal/v1/policies/{variationPoint}/relaxations`.
 *
 * The order of the checks is [JobCommandServlet]'s: an unknown route is 404 SRT-005, another method 405 SRT-006, no
 * valid token 401 SRT-003 (503 SRT-011 while the issuer is unreachable), then a request that breaks the contract
 * (body, `Idempotency-Key`, scope shape) is 400 SRT-001 or SRT-002, then the access checks are 403 SRT-004, all before
 * the `Idempotency-Key` store is touched or anything is bound:
 * - the actor (`sub`) and tenant (`tenant_id`) come from the propagated end-user token, never from a field (CS-23,
 *   API-24). A token without a tenant reaches only `scopeLevel` INSTALLATION, under the platform tenant (coordinator
 *   ruling INV-Q-4115); a token with a tenant never reaches INSTALLATION.
 * - for TENANT the `scopeRef` must be exactly the token tenant's canonical text (INV-Q-5233).
 * - for the entity-scoped levels the `entityRef` is checked against the token's entity set when it is current; when
 *   it is stale the set is not consulted and the proof is the [PolicyRoleResolver]'s roles on that `entityRef`
 *   (INV-Q-5235, applying INV-Q-3771 and INV-Q-3782).
 * - the roles come from [roles]; an unbound port, an error, no answer or an empty set is 403 SRT-004. The writer
 *   checks them against the setting role (POL-004).
 *
 * `entityRef` equal to `scopeRef` for ENTITY is not stateable in JSON Schema and is checked here with SRT-001
 * (INV-Q-5217), exactly (no trimming, no case folding), as are the present-exactly-when rules of `scopeRef` and
 * `entityRef`. A refusal of the writer is its POL code at the code's status ([PolicyErrorCode]). The
 * `Idempotency-Key` (API-15) is kept in [idempotency] with a fingerprint of the method, path and body; the command
 * and the key record commit in one transaction (TPL-04).
 */
class PolicyCommandServlet(
    private val writer: PolicyWriter,
    private val principals: EndUserPrincipalResolver,
    private val scope: TenantScope,
    private val idempotency: IdempotencyStore,
    private val roles: PolicyRoleResolver? = null,
) : HttpServlet() {
    private val mapper = ObjectMapper()

    override fun service(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val answer =
            try {
                handle(request, correlation)
            } catch (expected: RuntimeException) {
                log.error("policy command failed: {}", expected.javaClass.name) // CS-34: class only
                Problems.serverError(correlation)
            }
        response.status = answer.status
        response.contentType = if (answer.status >= HTTP_ERRORS) Problems.CONTENT_TYPE else JSON
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    private class Admitted(
        val principal: EndUserPrincipal,
        val parsed: Parsed,
        val granted: Set<String>,
        val body: ByteArray,
    )

    private fun handle(
        request: HttpServletRequest,
        correlation: String,
    ): StoredResponse {
        val path = CommandHttp.path(request)
        return when (val step = admit(request, path, correlation)) {
            is Step.Stop -> {
                step.response
            }

            is Step.Go -> {
                val admitted = step.value
                val principal = admitted.principal
                val fingerprint = CommandHttp.fingerprint(request.method, path, admitted.body)
                val outcome =
                    CommandHttp.bound(scope, principal) {
                        scope.transactions.inTransaction {
                            idempotency.once(principal.tenant, principal.actor, admitted.parsed.key, fingerprint) {
                                execute(admitted.parsed, Actor(principal.actor, admitted.granted), correlation)
                            }
                        }
                    }
                CommandHttp.answer(outcome, correlation)
            }
        }
    }

    private fun admit(
        request: HttpServletRequest,
        path: String,
        correlation: String,
    ): Step<Admitted> {
        val route = ROUTE.matchEntire(path) ?: return Step.Stop(Problems.notFound(correlation))
        val acknowledge = route.groupValues[2].isNotEmpty()
        return CommandHttp.caller(request, principals, correlation).andThen { principal ->
            val body = CommandHttp.body(request, MAX_BODY)
            CommandHttp
                .parsed(correlation) {
                    if (body.size > MAX_BODY) CommandViolation.raise("request body too large")
                    Parsed(
                        variationPoint = URLDecoder.decode(route.groupValues[1], StandardCharsets.UTF_8),
                        acknowledge = acknowledge,
                        key = CommandHttp.idempotencyKey(request),
                        body = CommandHttp.jsonObject(mapper, body),
                    )
                }.andThen { parsed ->
                    access(principal, parsed)?.let { Step.Go(Admitted(principal, parsed, it, body)) }
                        ?: Step.Stop(Problems.accessDenied(correlation))
                }
        }
    }

    /**
     * The access checks (CS-23, API-24; fail closed): the roles the actor holds at the target, or `null` for 403
     * SRT-004. Nothing is bound or stored before it answers.
     */
    private fun access(
        principal: EndUserPrincipal,
        parsed: Parsed,
    ): Set<String>? {
        val tenant = principal.tenant
        val level = parsed.level
        val entity = parsed.entity
        val inScope =
            (tenant == null) == (level == ScopeLevel.INSTALLATION) &&
                !(level == ScopeLevel.TENANT && parsed.scopeRef != tenant.toString()) &&
                !(entity != null && principal.entitySetCurrent && !principal.covers(entity))
        val held =
            if (inScope) {
                try {
                    roles?.rolesOf(principal.actor, tenant ?: TenantId.PLATFORM, level, parsed.scopeRef, entity?.value)
                } catch (_: RuntimeException) {
                    null
                }
            } else {
                null
            }
        return held?.takeIf { it.isNotEmpty() }
    }

    private inner class Parsed(
        val variationPoint: String,
        val acknowledge: Boolean,
        val key: UUID,
        val body: JsonNode,
    ) {
        val level: ScopeLevel = scopeLevel()
        val scopeRef: String? = optionalText("scopeRef")
        val entity: EntityRef?
        val selected: List<SelectedOption> = selectedOptions()
        val effectiveDate: LocalDate = effectiveDate()
        val reason: String? = optionalText("reason")
        val relaxation: RelaxationId? = optionalText("relaxationId")?.let { RelaxationId(uuid(it, "relaxationId")) }
        val asRole: String? = if (acknowledge) requiredText("asRole") else null
        val source: SettingSource = if (acknowledge) SettingSource.OVERRIDE else source()
        val profileRef: String? = if (acknowledge) null else profileRef()
        val profileVersion: String? = if (acknowledge) null else profileVersion()

        init {
            if (!VARIATION_POINT.matches(
                    variationPoint,
                )
            ) {
                CommandViolation.raise("variationPoint must match ^VP-[0-9]+$")
            }
            val entityRef = optionalText("entityRef")
            // the present-exactly-when rules of the pinned contract, whether or not the schema already refuses them
            if (scopeRef != null && scopeRef.isEmpty()) CommandViolation.raise("scopeRef must not be empty")
            if (entityRef != null && entityRef.isEmpty()) CommandViolation.raise("entityRef must not be empty")
            if ((scopeRef == null) != (level == ScopeLevel.INSTALLATION)) {
                CommandViolation.raise("scopeRef is absent exactly when scopeLevel is INSTALLATION")
            }
            if ((entityRef != null) != level.entityScoped) {
                CommandViolation.raise(
                    "entityRef is present exactly when scopeLevel is ENTITY, FEED, AP_CONNECTION or CLIENT_ENTITY",
                )
            }
            // INV-Q-5217: code-point equality, no trimming, no case folding; ENTITY only
            if (level == ScopeLevel.ENTITY && entityRef != scopeRef) {
                CommandViolation.raise("entityRef must equal scopeRef when scopeLevel is ENTITY")
            }
            entity =
                entityRef?.let {
                    try {
                        EntityRef(it)
                    } catch (_: IllegalArgumentException) {
                        CommandViolation.raise("entityRef is not a valid entity reference")
                    }
                }
            reason?.let {
                if (it.length >
                    MAX_REASON
                ) {
                    CommandViolation.raise("reason is at most $MAX_REASON characters")
                }
            }
        }

        private fun scopeLevel(): ScopeLevel {
            val text = requiredText("scopeLevel")
            return ScopeLevel.entries.firstOrNull { it.name == text }
                ?: CommandViolation.raise("scopeLevel is not a scope level")
        }

        private fun source(): SettingSource {
            val text = optionalText("source") ?: return SettingSource.OVERRIDE
            return SettingSource.entries.firstOrNull { it.name == text }
                ?: CommandViolation.raise("source is OVERRIDE or PROFILE")
        }

        private fun profileRef(): String? {
            val text = optionalText("profileRef") ?: return null
            if (text.isEmpty() || text.length > MAX_PROFILE_REF) {
                CommandViolation.raise("profileRef is 1 to $MAX_PROFILE_REF characters")
            }
            if (source != SettingSource.PROFILE) CommandViolation.raise("profileRef is only for source PROFILE")
            return text
        }

        private fun profileVersion(): String? {
            val node = body.get("profileVersion")?.takeUnless { it.isNull } ?: return null
            if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
                CommandViolation.raise("profileVersion must be an integer of at least 1")
            }
            if (source != SettingSource.PROFILE) CommandViolation.raise("profileVersion is only for source PROFILE")
            return node.asLong().toString()
        }

        private fun effectiveDate(): LocalDate =
            try {
                LocalDate.parse(requiredText("effectiveDate"))
            } catch (_: DateTimeParseException) {
                CommandViolation.raise("effectiveDate must be a date")
            }

        /** An empty `selected` is the writer's POL-012, not a shape error; a malformed item is SRT-001. */
        private fun selectedOptions(): List<SelectedOption> {
            val node = body.get("selected")
            if (node == null || !node.isArray) CommandViolation.raise("selected must be an array")
            return node.toList().map { item ->
                if (!item.isObject) CommandViolation.raise("a selected item must be an object")
                val option = item.get("option")
                if (option == null || !option.isString || option.asString().isEmpty()) {
                    CommandViolation.raise("a selected item names its option")
                }
                try {
                    SelectedOption(option.asString(), item.get("value")?.let { mapper.writeValueAsString(it) })
                } catch (_: IllegalArgumentException) {
                    CommandViolation.raise("a selected item is not a valid option")
                }
            }
        }

        private fun requiredText(field: String): String =
            optionalText(field)?.takeIf { it.isNotEmpty() } ?: CommandViolation.raise("$field is required")

        private fun optionalText(field: String): String? {
            val value = body.get(field)
            return when {
                value == null || value.isNull -> null
                !value.isString -> CommandViolation.raise("$field must be a string")
                else -> value.asString()
            }
        }

        private fun uuid(
            text: String,
            field: String,
        ): UUID =
            try {
                UUID.fromString(text).also {
                    if (it.toString() !=
                        text.lowercase()
                    ) {
                        CommandViolation.raise("$field must be a UUID")
                    }
                }
            } catch (_: IllegalArgumentException) {
                CommandViolation.raise("$field must be a UUID")
            }

        private fun entityValue(): String = (entity ?: throw NullPointerException("entity is null")).value

        /** The scope the writer receives: the token's tenant, never a field (CS-23). */
        fun policyScope(tenant: TenantId?): PolicyScope {
            val ref = scopeRef.orEmpty()
            return when (level) {
                ScopeLevel.INSTALLATION -> {
                    PolicyScope.installation()
                }

                ScopeLevel.TENANT -> {
                    PolicyScope.tenant(checkNotNull(tenant))
                }

                ScopeLevel.GROUP -> {
                    PolicyScope.tenant(checkNotNull(tenant), group = ref)
                }

                ScopeLevel.ENTITY -> {
                    PolicyScope.entity(checkNotNull(tenant), ref)
                }

                ScopeLevel.FEED -> {
                    PolicyScope.entity(
                        checkNotNull(tenant),
                        entityValue(),
                        feed = ref,
                    )
                }

                ScopeLevel.AP_CONNECTION -> {
                    PolicyScope.entity(
                        checkNotNull(tenant),
                        entityValue(),
                        apConnection = ref,
                    )
                }

                ScopeLevel.CLIENT_ENTITY -> {
                    PolicyScope.entity(
                        checkNotNull(tenant),
                        entityValue(),
                        clientEntity = ref,
                    )
                }
            }
        }
    }

    private fun execute(
        parsed: Parsed,
        actor: Actor,
        correlation: String,
    ): StoredResponse =
        try {
            val key = VariationPointKey(parsed.variationPoint.takeIf { KEY.matches(it) } ?: throw UnknownKey())
            val scopeOf = parsed.policyScope(scope.tenants.current().takeUnless { it.isPlatform })
            val correlationId = CorrelationId(correlation)
            val causation = CausationId("idempotency-key:${parsed.key}")
            if (parsed.acknowledge) {
                acknowledge(parsed, CommandContext(key, scopeOf, actor, correlationId, causation))
            } else {
                set(parsed, CommandContext(key, scopeOf, actor, correlationId, causation))
            }
        } catch (_: UnknownKey) {
            Problems.policy(PolicyErrorCode.UNKNOWN_VARIATION_POINT, correlation)
        } catch (_: UnknownVariationPoint) {
            Problems.policy(PolicyErrorCode.UNKNOWN_VARIATION_POINT, correlation)
        } catch (_: VariationPointRetired) {
            Problems.policy(PolicyErrorCode.VARIATION_POINT_RETIRED, correlation)
        } catch (refused: PolicyRefused) {
            Problems.policy(PolicyErrorCode.of(refused.refusal), correlation)
        } catch (invalid: IllegalArgumentException) {
            Problems.contractViolation(invalid.message.orEmpty(), correlation)
        }

    private class CommandContext(
        val key: VariationPointKey,
        val scopeOf: PolicyScope,
        val actor: Actor,
        val correlationId: CorrelationId,
        val causation: CausationId,
    )

    private fun acknowledge(
        parsed: Parsed,
        context: CommandContext,
    ): StoredResponse {
        val command =
            AcknowledgeRelaxationCommand(
                context.key,
                context.scopeOf,
                parsed.level,
                parsed.selected,
                parsed.effectiveDate,
                context.actor,
                checkNotNull(parsed.asRole),
                context.correlationId,
                context.causation,
                parsed.relaxation,
                parsed.reason,
            )
        return PolicyCommandResponses.relaxationResponse(writer.acknowledgeControlRelaxation(command))
    }

    private fun set(
        parsed: Parsed,
        context: CommandContext,
    ): StoredResponse {
        val command =
            SetPolicyCommand(
                context.key,
                context.scopeOf,
                parsed.level,
                parsed.selected,
                parsed.effectiveDate,
                parsed.reason,
                context.actor,
                context.correlationId,
                context.causation,
                parsed.source,
                parsed.profileRef,
                parsed.profileVersion,
                parsed.relaxation,
            )
        return PolicyCommandResponses.settingResponse(writer.setPolicy(command))
    }

    /** A key the catalogue could never register (config-resolver keys are VP-nn or VP-nnn): not registered. */
    private class UnknownKey : RuntimeException()

    companion object {
        /** The servlet mapping of the two operations. */
        const val MAPPING = "/internal/v1/policies/*"
        private val ROUTE = Regex("^/internal/v1/policies/([^/]+)(/relaxations)?$")
        private val VARIATION_POINT = Regex("^VP-[0-9]+$")
        private val KEY = Regex("^VP-[0-9]{2,3}$")
        private const val JSON = "application/json"
        private const val MAX_BODY = 64 * 1024
        private const val MAX_REASON = 500
        private const val MAX_PROFILE_REF = 64
        private const val HTTP_ERRORS = 400
        private val log = LoggerFactory.getLogger(PolicyCommandServlet::class.java)
    }
}
