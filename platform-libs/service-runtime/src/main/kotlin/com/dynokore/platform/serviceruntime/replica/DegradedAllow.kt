package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The operation a request is served by: its [method], its path [template] and the [principal] (token scope) the
 * operation takes. Only service-runtime makes one, from the route (INV-Q-9065 A): the constructor is internal, so a
 * service can pass on the key it reads from [ATTRIBUTE] but cannot write one.
 */
class OperationKey internal constructor(
    val method: String,
    val template: String,
    val principal: String,
) {
    override fun equals(other: Any?): Boolean =
        other is OperationKey && method == other.method && template == other.template && principal == other.principal

    override fun hashCode(): Int = listOf(method, template, principal).hashCode()

    override fun toString(): String = "$method $template"

    companion object {
        /** The request attribute that holds the [OperationKey] of a verified machine operation. */
        const val ATTRIBUTE = "com.dynokore.platform.serviceruntime.replica.OperationKey"
    }
}

/**
 * The operations the contract marks `x-degraded-allow: true`, as (method, path template) rows of
 * `META-INF/gst/degraded-allow-operations.tsv` (INV-Q-9065 A). [marks] is exact equality of both; the empty table
 * marks nothing, so every degraded MACHINE request is denied.
 */
class DegradedAllowTable(
    rows: Set<Pair<String, String>>,
) {
    private val rows: Set<Pair<String, String>> = rows.toSet()

    fun marks(operation: OperationKey): Boolean = (operation.method to operation.template) in rows

    companion object {
        val EMPTY = DegradedAllowTable(emptySet())
    }
}

/** One degraded allow, for the audit line and the counter; carries ids only, no payload. */
data class DegradedAllow(
    val operation: OperationKey,
    val tenant: TenantId,
    val principalId: String,
    val entity: String,
    val tokenId: String?,
) {
    /** The structured audit line (key=value, each value quoted and escaped). */
    fun line(): String =
        "authority_degraded_allow operation=${quoted(operation.toString())} tenant=${quoted(tenant.toString())} " +
            "principal=${quoted(principalId)} entity=${quoted(entity)} token=${quoted(tokenId ?: "")}"

    private fun quoted(value: String): String =
        buildString {
            append('"')
            for (c in value) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c.isISOControl() -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}

/** Told once per degraded allow, never for a denial; the impl half counts it (`authority_degraded_allow_total`). */
fun interface DegradedAllowObserver {
    fun onAllow(allow: DegradedAllow)
}
