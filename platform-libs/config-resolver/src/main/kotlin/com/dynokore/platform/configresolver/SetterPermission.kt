package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The only way [PolicyWriter] learns whether a caller may set a variation point or acknowledge a relaxation
 * (owner answer INV-Q-10027 (b)): a permission decision for one catalogue permission key at the scope of the call.
 * The writer asks it for the row's `vp-NN.set` key and, for an acknowledgement, for the key of
 * [acknowledgePermissionKey]; `false`, an exception and an unbound port alike are POL-004 (fail closed).
 */
fun interface SetterPermission {
    /**
     * @param actor the verified principal.
     * @param tenant the target's tenant; the platform tenant for [ScopeLevel.INSTALLATION].
     * @param scopeRef the target's scope id; `null` exactly for [ScopeLevel.INSTALLATION].
     * @param entityRef the entity of an entity-scoped level, else `null`.
     * @param permissionKey the catalogue permission key, `vp-NN.set` or an acknowledge key.
     */
    fun holds(
        actor: Actor,
        tenant: TenantId,
        level: ScopeLevel,
        scopeRef: String?,
        entityRef: String?,
        permissionKey: String,
    ): Boolean

    companion object {
        /** The setting permission of [key]: its id lower-cased with `.set`, compared by string equality. */
        fun settingKey(key: VariationPointKey): String = key.value.lowercase() + ".set"
    }
}

/**
 * The acknowledge permission key of [place] on [option] of the row [variationPointId] (INV-Q-10140 C): derived from
 * the catalogue row, with no catalogue field of its own. `vp-NN.acknowledge` when the option has one place,
 * `vp-NN.acknowledge.<role with dots replaced by hyphens>` when it has more.
 *
 * @throws IllegalArgumentException when [place] is not a place of [option], or a place of several has no single role
 *   (a by-form place), so that no key can be named (fail closed).
 */
fun acknowledgePermissionKey(
    variationPointId: String,
    option: OptionSpec,
    place: Acknowledger,
): String {
    val base = VariationPointKey(variationPointId).value.lowercase() + ".acknowledge"
    require(place in option.acknowledgers) { "$variationPointId option ${option.key}: not a place of the option" }
    if (option.acknowledgers.size == 1) return base
    val role = requireNotNull(place.role) { "$variationPointId option ${option.key}: a by-form place needs a role" }
    return base + "." + role.replace('.', '-')
}
