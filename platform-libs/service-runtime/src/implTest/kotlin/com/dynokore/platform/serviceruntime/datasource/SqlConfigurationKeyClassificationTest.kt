package com.dynokore.platform.serviceruntime.datasource

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lessons P140 for [SqlConfigurationKeyGuard]: every `spring.datasource.*` and `spring.flyway.*` key that Spring Boot's
 * configuration metadata on this classpath declares is classified, and the reviewed classification is pinned in
 * `sql-configuration-keys.txt`. A Spring Boot, HikariCP or Flyway upgrade that adds, removes or renames a key fails
 * here until the new list is reviewed (a new key is refused by default; allowing it needs a Keel ruling, INV-Q-4542).
 */
class SqlConfigurationKeyClassificationTest {
    @Test
    fun `every metadata key under spring-datasource and spring-flyway is classified as pinned`() {
        val actual = classify().joinToString("\n", postfix = "\n")
        val pinned = javaClass.getResource("/sql-configuration-keys.txt")?.readText().orEmpty()
        if (actual != pinned) {
            // the reviewer compares this with the pinned file
            Files.writeString(Path.of("build", "sql-configuration-keys.actual.txt"), actual)
        }
        actual shouldBe pinned
    }

    @Test
    fun `every allowlisted key exists in the metadata`() {
        val names = metadataKeys().map { ConfigurationPropertyName.of(it.first) }.toSet()
        val missing =
            listOf(
                "spring.datasource" to SqlConfigurationKeyGuard.DATASOURCE_ALLOWED,
                "spring.flyway" to SqlConfigurationKeyGuard.FLYWAY_ALLOWED,
            ).flatMap { (prefix, allowed) -> allowed.keys.map { "$prefix.$it".removeSuffix(".*") } }
                .filterNot { key -> names.any { uniform(it) == key.replace("-", "") } }
        missing.shouldBeEmpty()
    }

    private fun classify(): List<String> =
        metadataKeys()
            .filter { (name, _) -> PREFIXES.any { name.startsWith("$it.") } }
            .map { (name, map) ->
                // a map key is classified through one entry of it
                val key = if (map) "$name.x" else name
                val verdict = SqlConfigurationKeyGuard.refusal(ConfigurationPropertyName.of(key)) == null
                "${if (verdict) "allowed" else "refused"} $name"
            }.distinct()
            .sorted()

    private fun metadataKeys(): List<Pair<String, Boolean>> {
        val mapper = JsonMapper.builder().build()
        return javaClass.classLoader
            .getResources("META-INF/spring-configuration-metadata.json")
            .toList()
            .flatMap { url ->
                url.openStream().use { mapper.readTree(it) }["properties"]?.toList().orEmpty().map { property ->
                    property["name"].asString() to (property["type"]?.asString().orEmpty().startsWith("java.util.Map"))
                }
            }
    }

    private fun uniform(name: ConfigurationPropertyName) =
        (0 until name.numberOfElements).joinToString(".") {
            name.getElement(it, ConfigurationPropertyName.Form.UNIFORM)
        }

    private companion object {
        val PREFIXES = listOf("spring.datasource", "spring.flyway")
    }
}
