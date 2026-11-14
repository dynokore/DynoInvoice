package com.dynokore.gst.rules.domain.pack

/**
 * The engine-compatibility check of a pack import, step 2 (INV-REQ-1078 behaviour 2): a pack whose `min_engine_version`
 * is above the running train is refused with DEP-003. Versions are dot-separated non-negative integers (`1.0.0`,
 * `2026.4`); a missing segment counts as 0. A version that is not of that form is never compatible (fail closed).
 */
object EngineCompatibility {
    /** True when the pack's [minEngineVersion] is not above the [running] train; false for any unreadable version. */
    fun isCompatible(
        minEngineVersion: String,
        running: String,
    ): Boolean {
        val required = segments(minEngineVersion) ?: return false
        val current = segments(running) ?: return false
        val width = maxOf(required.size, current.size)
        for (i in 0 until width) {
            val need = required.getOrElse(i) { 0L }
            val have = current.getOrElse(i) { 0L }
            if (need != have) return need < have
        }
        return true
    }

    private fun segments(version: String): List<Long>? {
        if (!SHAPE.matches(version)) return null
        return version.split('.').map { it.toLongOrNull() ?: return null }
    }

    private val SHAPE = Regex("[0-9]{1,9}(\\.[0-9]{1,9})*")
}
