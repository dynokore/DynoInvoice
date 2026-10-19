package com.dynokore.platform.serviceruntime.datasource

import org.springframework.boot.context.properties.source.ConfigurationPropertyName

/** The decision logic of [SqlConfigurationKeyGuard.refusal], kept apart so the guard's companion stays small. */
internal object SqlKeyRefusals {
    const val PLACEHOLDERS = "placeholders"
    private const val HIKARI_CONFIGURATION_FILE_FLAT = "hikaricpconfigurationfile"
    private const val EVENT_LOG_URL_FLAT = "gstdbeventlogurl"
    private val DATASOURCE = ConfigurationPropertyName.of("spring.datasource")
    private val FLYWAY = ConfigurationPropertyName.of("spring.flyway")
    private val JMX = ConfigurationPropertyName.of("spring.jmx")
    private val EVENT_LOG_URL = ConfigurationPropertyName.of("gst.db.event-log-url")
    private val CONTEXT_PARAMETERS = ConfigurationPropertyName.of("server.servlet.context-parameters")

    fun refusal(
        name: ConfigurationPropertyName,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? {
        val elements =
            (0 until name.numberOfElements).map { name.getElement(it, ConfigurationPropertyName.Form.UNIFORM) }
        return when {
            // Tomcat hands a servlet-context init parameter to the environment once the web server exists, after this
            // guard ran: `server.servlet.context-parameters.<key>` is checked as `<key>`
            CONTEXT_PARAMETERS.isAncestorOf(name) -> {
                contextParameterRefusal(name, environmentVariable, value)
            }

            elements.joinToString("") == HIKARI_CONFIGURATION_FILE_FLAT -> {
                "an external pool configuration file (INV-Q-4559)"
            }

            else -> {
                keyRefusal(name, elements, environmentVariable, value)
            }
        }
    }

    private fun contextParameterRefusal(
        name: ConfigurationPropertyName,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? {
        val rest =
            (CONTEXT_PARAMETERS.numberOfElements until name.numberOfElements)
                .joinToString(".") { name.getElement(it, ConfigurationPropertyName.Form.ORIGINAL) }
        return SqlConfigurationKeyGuard
            .refusal(ConfigurationPropertyName.adapt(rest, '.'), environmentVariable, value)
            ?.let { "a servlet-context parameter that $it" }
    }

    private fun keyRefusal(
        name: ConfigurationPropertyName,
        elements: List<String>,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? {
        val key = withoutIndex(name, elements)
        return bootstrapRefusal(key, environmentVariable, value)
            ?: SqlConfigurationKeyGuard.REFUSED_PREFIXES
                .firstOrNull { it.isAncestorOf(name) || it == name }
                ?.let { "under $it" }
            ?: namespaceRefusal(name, elements, key, environmentVariable, value)
    }

    private fun bootstrapRefusal(
        key: List<String>,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? =
        SqlConfigurationKeyGuard.BOOTSTRAP_KEYS.entries
            .firstOrNull { (refused, _) ->
                key == refused || (environmentVariable && key.joinToString("") == refused.joinToString(""))
            }?.let { (_, check) -> check(value()) }

    private fun namespaceRefusal(
        name: ConfigurationPropertyName,
        elements: List<String>,
        key: List<String>,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? =
        when {
            JMX.isAncestorOf(name) || JMX == name -> {
                jmxRefusal(elements.drop(2), value)
            }

            else -> {
                sqlSegment(key, environmentVariable)?.let { "runs SQL: $it" }
                    ?: allowlistRefusal(name, elements, environmentVariable, value)
            }
        }

    private fun allowlistRefusal(
        name: ConfigurationPropertyName,
        elements: List<String>,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? {
        val eventLogUrl =
            name == EVENT_LOG_URL || (environmentVariable && elements.joinToString("") == EVENT_LOG_URL_FLAT)
        return when {
            DATASOURCE.isAncestorOf(name) || DATASOURCE == name -> {
                allowlisted(elements.drop(2), SqlConfigurationKeyGuard.DATASOURCE_ALLOWED, environmentVariable, value)
            }

            FLYWAY.isAncestorOf(name) || FLYWAY == name -> {
                allowlisted(elements.drop(2), SqlConfigurationKeyGuard.FLYWAY_ALLOWED, environmentVariable, value)
            }

            eventLogUrl -> {
                SqlConfigurationKeyGuard.urlRefusal(value())
            }

            else -> {
                null
            }
        }
    }

    /**
     * (7) Under `spring.jmx` only `enabled` with the value `false` (Spring Boot's default) passes: Spring's
     * MBeanExporter exports every HikariDataSource as HikariConfigMXBean, with a writable Password, whatever
     * registerMbeans is (coordinator ruling INV-Q-4591).
     */
    fun jmxRefusal(
        rest: List<String>,
        value: () -> String?,
    ): String? =
        if (rest == listOf("enabled") && value()?.trim().equals("false", ignoreCase = true)) {
            null
        } else {
            "JMX export is refused; only spring.jmx.enabled=false (INV-Q-4591)"
        }

    fun allowlisted(
        rest: List<String>,
        allowed: Map<String, (String?) -> String?>,
        environmentVariable: Boolean,
        value: () -> String?,
    ): String? {
        // a list value (`locations[0]`) is checked as its list key
        val key = rest.dropLastWhile { it.isNotEmpty() && it.all(Char::isDigit) }
        val joined = key.joinToString(".")
        val check =
            allowed[joined]
                ?: (
                    if (environmentVariable) {
                        allowed.entries
                            .firstOrNull {
                                it.key.replace(".", "") ==
                                    key.joinToString("")
                            }?.value
                    } else {
                        null
                    }
                )
                ?: (if (key.size >= 2 && key.first() == PLACEHOLDERS) allowed["$PLACEHOLDERS.*"] else null)
                ?: return "not on the allowlist"
        return check(value())
    }

    /** [elements] without the trailing list indexes and map keys (`init-sqls[0]`, `INITSQLS_0`, `init-sql[a]`). */
    fun withoutIndex(
        name: ConfigurationPropertyName,
        elements: List<String>,
    ): List<String> {
        var size = elements.size
        while (size > 1 && (name.chop(size).isLastElementIndexed || elements[size - 1].all(Char::isDigit))) size--
        return elements.take(size)
    }

    fun sqlSegment(
        elements: List<String>,
        environmentVariable: Boolean,
    ): String? {
        val last = elements.lastOrNull() ?: return null
        val joined = elements.joinToString("")
        val lastStart = joined.length - last.length
        return SqlConfigurationKeyGuard.SQL_SEGMENTS.firstOrNull { last.contains(it) }
            // FOO_INIT_SQL binds to foo.init-sql: a pattern spanning the last segments, ending in the last one
            ?: SqlConfigurationKeyGuard.SQL_SEGMENTS.firstOrNull { pattern ->
                environmentVariable &&
                    generateSequence(joined.indexOf(pattern)) { at ->
                        joined.indexOf(pattern, at + 1).takeIf { it >= 0 }
                    }.takeWhile { it >= 0 }
                        .any { at -> at + pattern.length > lastStart }
            }
    }
}
