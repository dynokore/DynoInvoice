package com.dynokore.platform.integrationcanon

import java.time.LocalDate

private const val MAX_CODE_LENGTH = 64

/** A specification version: an opaque, non-blank code of at most 64 characters. The library never reads inside it. */
@JvmInline
value class SpecVersion(
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "a specification version code must not be blank" }
        require(
            code.length <= MAX_CODE_LENGTH,
        ) { "a specification version code is at most $MAX_CODE_LENGTH characters" }
    }

    override fun toString(): String = code
}

/** The host's versions from oldest to newest. Codes are compared by string equality. */
class ReleaseOrder private constructor(
    val versions: List<SpecVersion>,
) {
    private val index: Map<SpecVersion, Int> = versions.withIndex().associate { (i, v) -> v to i }

    fun contains(version: SpecVersion): Boolean = version in index

    /** True when [a] comes before [b]; a version not in the list is refused. */
    fun isOlder(
        a: SpecVersion,
        b: SpecVersion,
    ): Boolean = positionOf(a) < positionOf(b)

    internal fun positionOf(version: SpecVersion): Int =
        requireNotNull(index[version]) { "version $version is not in the release order" }

    companion object {
        fun of(versions: List<SpecVersion>): ReleaseOrder {
            require(versions.isNotEmpty()) { "the release order must not be empty" }
            val duplicates =
                versions
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            require(duplicates.isEmpty()) { "the release order holds duplicate versions: $duplicates" }
            return ReleaseOrder(versions.toList())
        }
    }
}

/** A version accepted from [acceptedFrom] to [acceptedTo], both inclusive; a null [acceptedTo] is open-ended. */
data class AcceptanceEntry(
    val version: SpecVersion,
    val acceptedFrom: LocalDate,
    val acceptedTo: LocalDate?,
) {
    init {
        require(acceptedTo == null || !acceptedTo.isBefore(acceptedFrom)) {
            "acceptedTo $acceptedTo is before acceptedFrom $acceptedFrom for version $version"
        }
    }

    fun acceptsOn(date: LocalDate): Boolean =
        !date.isBefore(acceptedFrom) && (acceptedTo == null || !date.isAfter(acceptedTo))
}

/** The acceptance calendar: which versions are accepted on a date, decided by its entries and the release order. */
class AcceptanceCalendar private constructor(
    val order: ReleaseOrder,
    val entries: List<AcceptanceEntry>,
) {
    /** The versions accepted on [date], oldest first by the release order. */
    fun acceptedOn(date: LocalDate): List<SpecVersion> =
        entries.filter { it.acceptsOn(date) }.map { it.version }.sortedBy { order.positionOf(it) }

    companion object {
        fun of(
            order: ReleaseOrder,
            entries: List<AcceptanceEntry>,
        ): AcceptanceCalendar {
            require(entries.isNotEmpty()) { "the acceptance calendar must have at least one entry" }
            entries.forEach {
                require(
                    order.contains(it.version),
                ) { "entry version ${it.version} is not in the release order" }
            }
            val duplicates =
                entries
                    .groupingBy { it.version }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            require(duplicates.isEmpty()) { "more than one entry for version(s) $duplicates" }
            return AcceptanceCalendar(order, entries.toList())
        }
    }
}
