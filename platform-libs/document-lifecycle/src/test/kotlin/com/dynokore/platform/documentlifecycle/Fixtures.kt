package com.dynokore.platform.documentlifecycle

/** The SDS 33.2 states as TEST DATA only; the library's main and impl sources hold none of these codes. */
object Fixtures {
    val model =
        StateModel(
            states =
                listOf(
                    StateSpec("NEW"),
                    StateSpec("READY"),
                    StateSpec("AWAITING_PRECEDING"),
                    StateSpec("QUEUED", inFlight = true, countsAsOpen = true),
                    StateSpec("SENT", inFlight = true, countsAsOpen = true),
                    StateSpec("SENT_UNCONFIRMED", inFlight = true, countsAsOpen = true),
                    StateSpec("RETRY", inFlight = true, countsAsOpen = true),
                    StateSpec("ACKNOWLEDGED"),
                    StateSpec("CANCELLED"),
                    StateSpec("SUPERSEDED"),
                ),
            transitions =
                setOf(
                    "NEW" to "READY",
                    "NEW" to "AWAITING_PRECEDING",
                    "AWAITING_PRECEDING" to "READY",
                    "AWAITING_PRECEDING" to "CANCELLED",
                    "READY" to "QUEUED",
                    "READY" to "CANCELLED",
                    "QUEUED" to "SENT",
                    "QUEUED" to "CANCELLED",
                    "SENT" to "ACKNOWLEDGED",
                    "SENT" to "SENT_UNCONFIRMED",
                    "SENT" to "RETRY",
                    "SENT_UNCONFIRMED" to "RETRY",
                    "SENT_UNCONFIRMED" to "ACKNOWLEDGED",
                    "RETRY" to "SENT",
                    "RETRY" to "CANCELLED",
                    "ACKNOWLEDGED" to "SUPERSEDED",
                ).map { Transition(it.first, it.second) }.toSet(),
            initial = "NEW",
        )

    val cancelRule = SupersessionRule("READY", setOf("READY", "AWAITING_PRECEDING", "QUEUED"), "CANCELLED")
    val supersedeRule = SupersessionRule("ACKNOWLEDGED", setOf("ACKNOWLEDGED"), "SUPERSEDED")

    val lifecycle = DocumentLifecycle(model, listOf(cancelRule, supersedeRule))

    fun id(value: String = "doc-1") = LogicalId(value)

    /** A document whose versions are walked to the given states (each via allowed transitions, in order). */
    fun docAt(vararg paths: List<String>): VersionedDocument {
        var doc = lifecycle.open(id())
        paths.forEachIndexed { i, path ->
            if (i > 0) doc = doc.addVersion()
            path.forEach { doc = doc.transition(i + 1, it) }
        }
        return doc
    }

    fun states(doc: VersionedDocument) = doc.versions.map { it.state }
}
