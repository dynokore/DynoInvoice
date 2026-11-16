package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.domain.SpecificationFamily

/**
 * The specification family of an entity (VP-71) from a policy lookup. [lookup] answers the option key the policy
 * resolves for the entity. A key that names no [SpecificationFamily] is an [IllegalStateException], never a default.
 * The lookup itself (scope level and date of the policy path) is wired by the configuration.
 */
class PolicySpecificationFamilySource(
    private val lookup: FamilyLookup,
) : SpecificationFamilySource {
    override fun familyOf(entityRef: String): String {
        val key = lookup.optionKeyOf(entityRef) ?: error("no specification family resolved for entity $entityRef")
        return SpecificationFamily.entries.firstOrNull { it.name == key }?.name
            ?: error("specification family $key of entity $entityRef is not known")
    }
}

/** The policy lookup of the specification family option key of an entity; null when the policy resolves none. */
fun interface FamilyLookup {
    fun optionKeyOf(entityRef: String): String?
}
