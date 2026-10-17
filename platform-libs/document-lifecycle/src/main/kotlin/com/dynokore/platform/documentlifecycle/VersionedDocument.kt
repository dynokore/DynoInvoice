package com.dynokore.platform.documentlifecycle

/** An opaque, non-blank document identifier of at most 128 characters. */
@JvmInline
value class LogicalId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A logical id must not be blank" }
        require(value.length <= MAX_LENGTH) { "A logical id is at most $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 128
    }
}

/** One version of a document: its number (1, 2, 3, ...) and its current state code. */
data class DocumentVersion(
    val number: Int,
    val state: String,
)

/** The move is not an allowed transition of the model. */
class IllegalTransitionException(
    val id: LogicalId,
    val version: Int,
    val from: String,
    val to: String,
) : RuntimeException("Document $id version $version cannot move from '$from' to '$to'")

/** Entering an in-flight state while another version of the same document is in flight. */
class InFlightConflictException(
    val id: LogicalId,
    val version: Int,
    val otherVersion: Int,
) : RuntimeException("Document $id version $version cannot enter flight while version $otherVersion is in flight")

/**
 * An immutable versioned document: versions numbered 1, 2, 3 without gaps. Every operation returns a new value and
 * leaves the receiver unchanged. The in-flight guard is derived from the model's `inFlight` flags; supersession comes
 * from [rules]. A supersession move is not a trigger itself (no cascade) but is checked against the guard.
 */
class VersionedDocument private constructor(
    val id: LogicalId,
    val rules: SupersessionRules,
    val versions: List<DocumentVersion>,
) {
    private val model: StateModel get() = rules.model

    /** Appends version n+1 in the model's initial state; no earlier version's number or content changes. */
    fun addVersion(): VersionedDocument {
        val number = versions.size + 1
        val withNew = versions + DocumentVersion(number, model.initial)
        return VersionedDocument(id, rules, withNew).enter(number, model.initial)
    }

    /** Moves [version] to [to], then applies the supersession rules triggered by [to]. */
    fun transition(
        version: Int,
        to: String,
    ): VersionedDocument {
        val current = get(version)
        if (!model.allows(current.state, to)) throw IllegalTransitionException(id, version, current.state, to)
        return replace(version, to).enter(version, to)
    }

    fun get(version: Int): DocumentVersion =
        requireNotNull(versions.getOrNull(version - 1)) { "Document $id has no version $version" }

    private fun enter(
        version: Int,
        state: String,
    ): VersionedDocument {
        if (model.isInFlight(state)) {
            val other = versions.firstOrNull { it.number != version && model.isInFlight(it.state) }
            if (other != null) throw InFlightConflictException(id, version, other.number)
        }
        var doc = this
        for (rule in rules.triggeredBy(state)) {
            for (old in doc.versions.filter { it.number < version && it.state in rule.older }) {
                doc = doc.replace(old.number, rule.becomes).also { it.checkGuard(old.number, rule.becomes) }
            }
        }
        return doc
    }

    private fun checkGuard(
        version: Int,
        state: String,
    ) {
        if (!model.isInFlight(state)) return
        val other = versions.firstOrNull { it.number != version && model.isInFlight(it.state) }
        if (other != null) throw InFlightConflictException(id, version, other.number)
    }

    private fun replace(
        version: Int,
        to: String,
    ): VersionedDocument =
        VersionedDocument(id, rules, versions.map { if (it.number == version) it.copy(state = to) else it })

    override fun equals(other: Any?): Boolean =
        other is VersionedDocument && id == other.id && versions == other.versions && rules === other.rules

    override fun hashCode(): Int = 31 * id.hashCode() + versions.hashCode()

    override fun toString(): String = "VersionedDocument($id, $versions)"

    companion object {
        /** A document with no version yet. */
        fun create(
            id: LogicalId,
            rules: SupersessionRules,
        ): VersionedDocument = VersionedDocument(id, rules, emptyList())
    }
}
