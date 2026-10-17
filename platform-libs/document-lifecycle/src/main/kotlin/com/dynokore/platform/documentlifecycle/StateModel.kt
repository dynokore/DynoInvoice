package com.dynokore.platform.documentlifecycle

/** One state of a [StateModel]: an opaque code and the two flags the library derives its rules from. */
data class StateSpec(
    val code: String,
    val inFlight: Boolean = false,
    val countsAsOpen: Boolean = false,
)

/** An allowed move from one state code to another. */
data class Transition(
    val from: String,
    val to: String,
)

/**
 * The state model of one embedding service, all of it data (INV-MOD-2741: mechanism and generic types; the service
 * instantiates it with its own types). A state is terminal exactly when it has no outgoing transition; that is derived
 * in [isTerminal], never a flag. [initial] is the state [VersionedDocument.addVersion] gives a new version.
 *
 * Construction throws [IllegalArgumentException] for an empty state set, a blank or duplicate state code, a
 * transition that names an unknown state, and an initial state that is not in the set.
 */
class StateModel(
    states: List<StateSpec>,
    transitions: Set<Transition>,
    val initial: String,
) {
    private val byCode: Map<String, StateSpec>
    private val allowed: Set<Transition> = transitions.toSet()

    init {
        require(states.isNotEmpty()) { "A state model needs at least one state" }
        val seen = LinkedHashMap<String, StateSpec>()
        for (state in states) {
            require(state.code.isNotBlank()) { "A state code must not be blank" }
            require(seen.put(state.code, state) == null) { "Duplicate state code '${state.code}'" }
        }
        byCode = seen
        for (t in allowed) {
            require(t.from in byCode) { "Transition ${t.from} -> ${t.to} names unknown state '${t.from}'" }
            require(t.to in byCode) { "Transition ${t.from} -> ${t.to} names unknown state '${t.to}'" }
        }
        require(initial in byCode) { "Initial state '$initial' is not a state of the model" }
    }

    val states: Set<String> get() = byCode.keys

    fun contains(code: String): Boolean = code in byCode

    fun allows(
        from: String,
        to: String,
    ): Boolean = Transition(from, to) in allowed

    /** True exactly when [code] has no outgoing transition. */
    fun isTerminal(code: String): Boolean {
        require(code in byCode) { "Unknown state '$code'" }
        return allowed.none { it.from == code }
    }

    fun isInFlight(code: String): Boolean = spec(code).inFlight

    fun countsAsOpen(code: String): Boolean = spec(code).countsAsOpen

    private fun spec(code: String): StateSpec = requireNotNull(byCode[code]) { "Unknown state '$code'" }
}
