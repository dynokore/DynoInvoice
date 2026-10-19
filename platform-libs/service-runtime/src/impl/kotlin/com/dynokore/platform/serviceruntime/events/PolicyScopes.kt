package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.tenancykernel.TenantId

/** The payload scope of a `policyChanged` event as a [PolicyScope]; split from [PlatformEventReader]. */
internal object PolicyScopes {
    fun of(
        envelope: Envelope,
        text: String,
    ): PolicyScope = if (text == "INSTALLATION") installationScope(envelope) else levelScope(envelope, text)

    private fun installationScope(envelope: Envelope): PolicyScope {
        ensure(envelope.tenant.isPlatform) { "an installation value carries no tenant" }
        return PolicyScope.installation()
    }

    private fun levelScope(
        envelope: Envelope,
        text: String,
    ): PolicyScope {
        val levelName = text.substringBefore(':', "")
        val level =
            ScopeLevel.entries.firstOrNull { it.name == levelName }
                ?: throw ContractViolation("unknown ScopeLevel '$levelName'")
        val id = text.substringAfter(':', "")
        ensure(!(id.isBlank() || level == ScopeLevel.INSTALLATION)) { "scope must be INSTALLATION or LEVEL:id" }
        ensure(!envelope.tenant.isPlatform) { "a $level value carries its tenant" }
        val entity = envelope.entity.takeUnless { it == EntityRef.TENANT_WIDE }?.value
        ensure(!(level.entityScoped && entity == null)) { "a $level value carries its entity" }
        return scopeAt(level, envelope.tenant, id, entity)
    }

    private fun scopeAt(
        level: ScopeLevel,
        tenant: TenantId,
        id: String,
        entity: String?,
    ): PolicyScope =
        when (level) {
            ScopeLevel.TENANT -> PolicyScope.tenant(tenant)
            ScopeLevel.GROUP -> PolicyScope.tenant(tenant, group = id)
            ScopeLevel.ENTITY -> PolicyScope.entity(tenant, id)
            ScopeLevel.FEED -> PolicyScope.entity(tenant, entityOf(entity), feed = id)
            ScopeLevel.AP_CONNECTION -> PolicyScope.entity(tenant, entityOf(entity), apConnection = id)
            ScopeLevel.CLIENT_ENTITY -> PolicyScope.entity(tenant, entityOf(entity), clientEntity = id)
            ScopeLevel.INSTALLATION -> PolicyScope.installation()
        }

    private fun entityOf(entity: String?): String = entity ?: throw NullPointerException("entity is null")

    private inline fun ensure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw ContractViolation(message())
    }
}
