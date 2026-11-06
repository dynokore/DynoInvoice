package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Test stand-in for the permission decision path (INV-Q-10027): a caller holds a permission key exactly when it
 * holds a role the fixture registered for that key. An actor is known by identity, so two actors with one principal
 * name may hold different roles. Production code never sees a role; only this fake maps them.
 */
object RoleGrants {
    private val keyRoles = ConcurrentHashMap<String, Set<String>>()
    private val held = Collections.synchronizedMap(IdentityHashMap<Actor, Set<String>>())

    /** An actor holding [roles]. */
    fun actor(
        principal: String,
        vararg roles: String,
    ): Actor = Actor(principal).also { held[it] = roles.toSet() }

    /** Registers the roles that hold the setting permission of [key] and returns that permission key. */
    fun setting(
        key: VariationPointKey,
        roles: Set<String>,
    ): String = SetterPermission.settingKey(key).also { keyRoles[it] = roles }

    /** Registers the roles of every acknowledge key of [definition]. */
    fun register(definition: VariationPointDefinition): VariationPointDefinition {
        definition.options.forEach { option ->
            option.acknowledgers.forEach { place ->
                val key = acknowledgePermissionKey(definition.key.value, option, place)
                val roles = listOfNotNull(place.role) + place.roleByForm.values
                keyRoles.merge(key, roles.toSet()) { a, b -> a + b }
            }
        }
        return definition
    }

    /** True when the actor holds a role registered for the key. */
    val permission =
        SetterPermission { actor: Actor, _: TenantId, _: ScopeLevel, _: String?, _: String?, key: String ->
            val roles = held[actor].orEmpty()
            keyRoles[key].orEmpty().any { it in roles }
        }
}
