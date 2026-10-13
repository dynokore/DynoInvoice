package com.dynokore.platform.tenancyjooq

import org.jooq.Configuration
import org.jooq.Result
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult

/**
 * Runs statements of a configuration against jOOQ's mock JDBC driver instead of a database, for the render-overhead
 * benchmark (INV-T-4133): every statement returns [result] (or no rows), so only rendering and the listeners are timed.
 */
object MockExecution {
    fun configuration(
        configuration: Configuration,
        result: Result<*>? = null,
    ): Configuration = configuration.derive(
        MockConnection { arrayOf(if (result == null) MockResult(0) else MockResult(result.size, result)) },
    )
}
