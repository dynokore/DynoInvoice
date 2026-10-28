package com.dynokore.platform.serviceruntime.securityevents

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `gst.security-events.*` (INV-REQ-1384; owner 2026-10-26: INV-Q-10904 and INV-Q-10906 are configurable parameters).
 * The defaults live in `service-runtime-security-events-defaults.properties`, not in code.
 *
 * @property classAttribute the log-record attribute that carries the class wire value.
 * @property allowedAttributes the only attribute names a record may carry; a record holding another is dropped.
 * @property queueCapacity records the sender queue holds; a full queue drops the record.
 * @property retryMaxAttempts attempts per record, the first included.
 * @property retryBackoffMillis wait between two attempts.
 * @property mapping the exact audit action string to the wire value of the class it raises (INV-Q-11054); keys are
 *   compared by equality and a service's entries merge with the packaged one by key.
 */
@ConfigurationProperties(prefix = SecurityEventProperties.PREFIX)
class SecurityEventProperties(
    val classAttribute: String,
    val allowedAttributes: List<String>,
    val queueCapacity: Int,
    val retryMaxAttempts: Int,
    val retryBackoffMillis: Long,
    val mapping: Map<String, String>,
) {
    init {
        require(classAttribute.isNotBlank()) { "$PREFIX.class-attribute must not be blank" }
        require(allowedAttributes.isNotEmpty() && allowedAttributes.none { it.isBlank() }) {
            "$PREFIX.allowed-attributes must hold at least one attribute name and no blank one"
        }
        require(classAttribute in allowedAttributes) {
            "$PREFIX.allowed-attributes must hold the $PREFIX.class-attribute '$classAttribute'"
        }
        require(queueCapacity >= 1) { "$PREFIX.queue-capacity must be at least 1, was $queueCapacity" }
        require(retryMaxAttempts >= 1) { "$PREFIX.retry-max-attempts must be at least 1, was $retryMaxAttempts" }
        require(
            retryBackoffMillis >= 0,
        ) { "$PREFIX.retry-backoff-millis must not be negative, was $retryBackoffMillis" }
        mapping.forEach { (action, wire) ->
            require(action.isNotBlank()) { "$PREFIX.mapping must not hold a blank key" }
            require(SecurityClass.fromWire(wire) != null) {
                "$PREFIX.mapping[$action] must be one of ${SecurityClass.entries.map { it.wireValue }}, was '$wire'"
            }
        }
    }

    /** The class [action] raises, by string equality; null when [action] is not a key of [mapping]. */
    fun classOf(action: String): SecurityClass? = mapping[action]?.let(SecurityClass::fromWire)

    companion object {
        const val PREFIX = "gst.security-events"
    }
}
