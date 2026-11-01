package com.dynokore.gst.transport.domain

/** The elected way an acknowledgement arrives for an Observe-mode AP connection (INV-REQ-1310). There is no default. */
enum class AcknowledgementRoute {
    AP_CALLBACK,
    POLLING,
    REPORT_IMPORT,
    ;

    companion object {
        /** Exact, case-sensitive: any other text is a validation error and nothing changes. */
        fun parse(text: String?): AcknowledgementRoute =
            entries.firstOrNull { it.name == text } ?: throw InvalidAcknowledgementRoute(text)
    }
}

/** A fourth route value (or a different spelling of one) was offered: a validation error. */
class InvalidAcknowledgementRoute(
    val offered: String?,
) : IllegalArgumentException("'$offered' is not an acknowledgement route; one of AP_CALLBACK, POLLING, REPORT_IMPORT")

/** The route state of one AP connection: the elected route or none, the entity refs listed on it, and the version. */
data class ConnectionRouteState(
    val route: AcknowledgementRoute?,
    val entityRefs: List<EntityRef>,
    val version: Long,
) {
    init {
        require(version >= 0) { "a version is not negative" }
    }

    /** Electing the stored value again is a no-op that keeps the version. */
    fun elect(elected: AcknowledgementRoute): RouteChange =
        if (route == elected) RouteChange.NoOp(this) else changed(elected, entityRefs)

    /** Clearing a clear route is a no-op that keeps the version. */
    fun clear(): RouteChange = if (route == null) RouteChange.NoOp(this) else changed(null, entityRefs)

    /** A change of the entity refs raises the version by one; the same list is a no-op. */
    fun withEntityRefs(refs: List<EntityRef>): RouteChange =
        if (refs == entityRefs) RouteChange.NoOp(this) else changed(route, refs)

    private fun changed(
        newRoute: AcknowledgementRoute?,
        refs: List<EntityRef>,
    ): RouteChange = RouteChange.Changed(ConnectionRouteState(newRoute, refs, version + 1))
}

sealed interface RouteChange {
    val state: ConnectionRouteState

    data class NoOp(
        override val state: ConnectionRouteState,
    ) : RouteChange

    data class Changed(
        override val state: ConnectionRouteState,
    ) : RouteChange
}
