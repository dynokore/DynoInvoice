package com.dynokore.platform.integrationcanon

/** Thrown by [ProjectionRegistry.project] for a version with no registered projection. */
class UnknownProjectionVersion(
    val version: SpecVersion,
) : RuntimeException("no projection registered for version $version")

/**
 * Per version, a projection from a canonical value [C] to an outbound value [P]. Immutable after [Builder.build];
 * [project] is a pure call of the registered function.
 */
class ProjectionRegistry<C, P> private constructor(
    private val projections: Map<SpecVersion, (C) -> P>,
) {
    fun project(
        version: SpecVersion,
        canonical: C,
    ): P = (projections[version] ?: throw UnknownProjectionVersion(version))(canonical)

    class Builder<C, P> {
        private val projections = LinkedHashMap<SpecVersion, (C) -> P>()

        fun register(
            version: SpecVersion,
            projection: (C) -> P,
        ): Builder<C, P> {
            require(version !in projections) { "a projection is already registered for version $version" }
            projections[version] = projection
            return this
        }

        fun build(): ProjectionRegistry<C, P> = ProjectionRegistry(LinkedHashMap(projections))
    }

    companion object {
        fun <C, P> builder(): Builder<C, P> = Builder()
    }
}
