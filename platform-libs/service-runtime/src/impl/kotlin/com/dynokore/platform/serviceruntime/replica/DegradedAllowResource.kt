package com.dynokore.platform.serviceruntime.replica

import io.micrometer.core.instrument.MeterRegistry
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Loads `META-INF/gst/degraded-allow-operations.tsv` from the classpath, as `EntityRefScopeFilter.RESOURCE` is
 * (INV-Q-9065 A, INV-Q-13626 A): the header row `operationId<TAB>method<TAB>path`, then one row per operation the
 * contract marks `x-degraded-allow: true`, sorted by operationId, none repeated. No resource gives the empty table
 * (every degraded MACHINE request is denied) and one WARN (INV-Q-13628 A). A malformed, unsorted or repeated line stops
 * the start-up; the message names the resource and the line number, never the line.
 */
internal object DegradedAllowResource {
    const val RESOURCE = "META-INF/gst/degraded-allow-operations.tsv"
    const val HEADER = "operationId\tmethod\tpath"

    private val LOG = System.getLogger(DegradedAllowResource::class.java.name)
    private val OPERATION_ID = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    private val METHOD = Regex("^[A-Z]{3,10}$")
    private val TEMPLATE = Regex("^/[^\\s\\p{Cntrl}]*$")

    fun load(loader: ClassLoader): DegradedAllowTable {
        val urls = loader.getResources(RESOURCE).toList()
        if (urls.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "$RESOURCE is absent: every degraded MACHINE request is denied")
            return DegradedAllowTable.EMPTY
        }
        val rows = mutableSetOf<Pair<String, String>>()
        val first = mutableMapOf<Pair<String, String>, String>()
        val ids = mutableMapOf<String, String>()
        for (url in urls) {
            for ((index, row) in rowsOf(url).withIndex()) {
                val where = "$RESOURCE $url line ${index + 2}"
                val earlier = first.putIfAbsent(row.second, where) ?: ids.putIfAbsent(row.first, where)
                check(earlier == null) { "$where: repeats the operation of $earlier" }
                rows += row.second
            }
        }
        return DegradedAllowTable(rows)
    }

    private fun rowsOf(url: URL): List<Pair<String, Pair<String, String>>> {
        val text = url.openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        val lines = text.removeSuffix("\n").split('\n')
        check(lines.first() == HEADER) { "$RESOURCE $url line 1: the header row must be $HEADER" }
        var previous: String? = null
        return lines.drop(1).mapIndexed { index, line ->
            val where = "$RESOURCE $url line ${index + 2}"
            val parts = line.split('\t')
            check(parts.size == 3) { "$where: a line needs exactly 3 TAB-separated fields" }
            check(OPERATION_ID.matches(parts[0])) { "$where: the operationId must be letters, digits and underscores" }
            check(METHOD.matches(parts[1])) { "$where: the method must be upper-case letters" }
            check(
                TEMPLATE.matches(parts[2]),
            ) { "$where: the path must start with / and hold no space or control character" }
            check(previous.let { it == null || it < parts[0] }) { "$where: the rows must be sorted by operationId" }
            previous = parts[0]
            parts[0] to (parts[1] to parts[2])
        }
    }
}

/** Counts each degraded allow in `authority_degraded_allow_total`, labelled by operation; no registry, no count. */
internal class MicrometerDegradedAllowObserver(
    private val meters: MeterRegistry?,
) : DegradedAllowObserver {
    override fun onAllow(allow: DegradedAllow) {
        meters?.counter(METER_NAME, "operation", allow.operation.toString())?.increment()
    }

    companion object {
        const val METER_NAME = "authority_degraded_allow_total"
    }
}

/** Counts the break-glass counters by name (`break_glass_event_ignored_total`, `break_glass_refused_total`); no registry, no count. */
internal class MicrometerBreakGlassCounter(
    private val meters: MeterRegistry?,
) : BreakGlassCounter {
    override fun increment(name: String) {
        meters?.counter(name)?.increment()
    }
}
