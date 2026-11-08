package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The scope a role grant is held at (INV-Q-8972 A): the seven wire scopes of `AuthorityChanged`. [UNKNOWN] is any
 * wire value this version does not recognise; it matches nothing (fail closed). The match is by exact name.
 */
enum class GrantScope {
    TENANT,
    INSTALLATION,
    GROUP,
    ENTITY,
    ENTITY_PERIOD,
    CONSENT,
    CLIENT_ENTITY,
    UNKNOWN,
    ;

    companion object {
        /** Maps a wire value; anything but the exact name of a member is [UNKNOWN]. */
        fun of(wire: String): GrantScope = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/** A role grant record's state; [UNKNOWN] is any unrecognised wire value and removes the grant (fail closed). */
enum class RoleGrantState {
    ACTIVE,
    REMOVED,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): RoleGrantState = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/**
 * One role held at one scope with its validity window (INV-REQ-864, INV-Q-8972 A). [scopeRef] is the tenant, group,
 * entity or client entity the scope names; null at INSTALLATION. An ENTITY_PERIOD grant holds its period span in
 * [periodFrom] and [periodTo] and never answers a role question (a role answer has no period).
 */
data class RoleGrant(
    val role: String,
    val scope: GrantScope,
    val scopeRef: String?,
    val validFrom: Instant,
    val validUntil: Instant? = null,
    val periodFrom: LocalDate? = null,
    val periodTo: LocalDate? = null,
) {
    /** Inside its validity window at [at]. */
    fun isValidAt(at: Instant): Boolean = !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil))
}

/** One role grant's key: one grant of a principal, named by the source's [grantRef]. */
data class RoleGrantKey(
    val tenant: TenantId,
    val principalId: String,
    val grantRef: String,
)

/** The keys that carry a role, per (tenant, role); an entry may be stale, so a reader re-reads the value. */
internal class RoleIndex {
    private val keys = ConcurrentHashMap<Pair<TenantId, String>, MutableSet<RoleGrantKey>>()

    fun add(
        key: RoleGrantKey,
        role: String,
    ) {
        keys.computeIfAbsent(key.tenant to role) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    fun remove(
        key: RoleGrantKey,
        role: String,
    ) {
        keys[key.tenant to role]?.let { held ->
            held.remove(key)
            if (held.isEmpty()) keys.remove(key.tenant to role, held)
        }
    }

    fun clear() = keys.clear()

    fun keysOf(
        tenant: TenantId,
        role: String,
    ): List<RoleGrantKey> = keys[tenant to role]?.toList().orEmpty()
}
