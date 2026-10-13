package com.dynokore.platform.serviceruntime.logging

/**
 * The MDC allow-list (STK-35, CS-34): the only keys any log event may carry
 * through Mapped Diagnostic Context. Every value on this list is an identifier,
 * never invoice content (CS-34: amounts, names, addresses, lines or free text
 * never appear in a log event). Application code sets MDC only through
 * [name] on this closed enum — never a raw string key — so a value that is not
 * one of these six can never be written through this module's own API; a
 * defence-in-depth Logback turbo filter
 * ([MdcAllowListTurboFilter][com.dynokore.platform.serviceruntime.logging.MdcAllowListTurboFilter])
 * additionally strips any other key a third-party library might set directly.
 */
enum class MdcKey(
    val key: String,
) {
    CORRELATION_ID("correlation_id"),
    CAUSATION_ID("causation_id"),
    TENANT_ID("tenant_id"),
    ENTITY_REF("entity_ref"),
    DOC_UUID("doc_uuid"),
    PACK_VERSION("pack_version"),
    ;

    companion object {
        /** The allow-listed key names, for the turbo filter and its tests. */
        val ALLOWED_NAMES: Set<String> = entries.map { it.key }.toSet()
    }
}
