package com.dynokore.gst.consoleBff.application.override

enum class Route { PAYLOAD, SUBMISSION }

/** Codes routed by exact equality; any other code is refused (fail closed). */
object RouteTable {
    private val rows: Map<String, Route> =
        mapOf(
            "PAY-006" to Route.PAYLOAD,
            "PAY-011" to Route.PAYLOAD,
            "PER-001" to Route.SUBMISSION,
        )

    fun routeOf(code: String): Route? = rows[code]
}
