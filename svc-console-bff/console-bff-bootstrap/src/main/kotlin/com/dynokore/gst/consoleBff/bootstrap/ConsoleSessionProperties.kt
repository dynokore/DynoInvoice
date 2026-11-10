package com.dynokore.gst.consoleBff.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `console.session.*` (INV-Q-14429, NIST SP 800-63B AAL2). A value that is missing, unparsable, zero or negative, or an
 * idle timeout above the absolute one, fails the start with a message naming the property. The constructor takes
 * nullable values so that a missing one is reported by the property name, not by the Kotlin parameter name.
 */
@ConfigurationProperties("console.session")
class ConsoleSessionProperties(
    idleTimeout: Duration?,
    absoluteTimeout: Duration?,
    sweepInterval: Duration?,
) {
    val idleTimeout: Duration = positive("console.session.idle-timeout", idleTimeout)
    val absoluteTimeout: Duration = positive("console.session.absolute-timeout", absoluteTimeout)
    val sweepInterval: Duration = positive("console.session.sweep-interval", sweepInterval)

    init {
        require(this.idleTimeout <= this.absoluteTimeout) {
            "console.session.idle-timeout (${this.idleTimeout}) must not be greater than " +
                "console.session.absolute-timeout (${this.absoluteTimeout})"
        }
    }

    private companion object {
        fun positive(
            name: String,
            value: Duration?,
        ): Duration {
            requireNotNull(value) { "$name is required" }
            require(!value.isZero && !value.isNegative) { "$name must be positive, was $value" }
            return value
        }
    }
}
