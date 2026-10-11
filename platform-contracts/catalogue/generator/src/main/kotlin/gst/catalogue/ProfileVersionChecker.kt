package gst.catalogue

/**
 * INV-Q-3283 A′: a profile's version is a positive whole number that never decreases and
 * is never reused for different values. "Values" is the structured value set (variation
 * point, option keys, typed values, alternatives, conditions, required-at-apply marks), not
 * the status, title or the values' prose ([ParsedProfileValue.comparable]). Compared with
 * the previous vendored snapshot (previous-snapshot.yaml); the first snapshot has none, so
 * the check starts with the second. [differences] is the per-profile difference INV-REQ-1245
 * b4 shows when an update is offered. Pure (no I/O).
 */
object ProfileVersionChecker {
    fun check(
        previous: List<ParsedOperatingProfile>,
        current: List<ParsedOperatingProfile>,
    ): List<String> {
        val now = current.associateBy { it.id }
        return previous.mapNotNull { old ->
            val cur = now[old.id]
            when {
                cur == null -> "${old.id}: in the previous snapshot, absent from this one"
                cur.version < old.version -> "${old.id}: version ${cur.version} is lower than the previous ${old.version}"
                cur.version == old.version && differences(old.values, cur.values).isNotEmpty() ->
                    "${old.id}: values changed (${differences(old.values, cur.values).joinToString { "${it.vp} ${it.kind}" }}) " +
                        "without a higher version than ${old.version}"
                else -> null
            }
        }
    }

    /** What changes, per variation point, from [before] to [after], in VP order. */
    fun differences(
        before: List<ParsedProfileValue>,
        after: List<ParsedProfileValue>,
    ): List<ProfileValueChange> {
        val b = before.associateBy { it.vp }
        val a = after.associateBy { it.vp }
        return (b.keys + a.keys).sortedBy { it.removePrefix("VP-").toInt() }.mapNotNull { vp ->
            val old = b[vp]
            val new = a[vp]
            when {
                old == null -> ProfileValueChange(vp, ProfileChangeKind.ADDED, null, new)
                new == null -> ProfileValueChange(vp, ProfileChangeKind.REMOVED, old, null)
                old.comparable() != new.comparable() -> ProfileValueChange(vp, ProfileChangeKind.CHANGED, old, new)
                else -> null
            }
        }
    }
}
