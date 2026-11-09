package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The idempotency and config-resolver migrations hold exactly what the pinned service-runtime and config-resolver
 * generate for schema `rules` (TPL-12), committed as text and never written by hand (INV-T-18451). The compared text
 * is the file after its leading `--` comment lines. A V file is never rewritten; a generator change is a new forward
 * migration. Both versions are above the highest migration that was on main when the task started, and the
 * idempotency file sorts before the config file (coordinator rule G-MIG).
 */
class IdempotencyConfigMigrationsTest {
    private val dir = File("src/main/resources/db/migration")
    private val idempotencyFile = dir.listFiles { f -> f.name.endsWith("__create_idempotency.sql") }.orEmpty()
    private val configFile = dir.listFiles { f -> f.name.endsWith("__create_config_resolver.sql") }.orEmpty()

    @Test
    fun `exactly one idempotency file and one config-resolver file exist`() {
        idempotencyFile.size shouldBe 1
        configFile.size shouldBe 1
    }

    @Test
    fun `the idempotency migration is the DDL service-runtime generates for the rules schema`() {
        body(idempotencyFile.single().readText()) shouldBe
            IdempotencySchema.ddl("rules", "rules_runtime", "rules_sys").trim()
    }

    @Test
    fun `the config-resolver migration is the DDL config-resolver generates for the rules schema`() {
        body(configFile.single().readText()) shouldBe ConfigSchema.ddl("rules", "rules_runtime", "rules_sys").trim()
    }

    @Test
    fun `both versions are above the highest earlier migration`() {
        (version(idempotencyFile.single().name) > PREVIOUS_HIGHEST) shouldBe true
        (version(configFile.single().name) > PREVIOUS_HIGHEST) shouldBe true
    }

    @Test
    fun `the idempotency file sorts before the config-resolver file`() {
        (version(idempotencyFile.single().name) < version(configFile.single().name)) shouldBe true
    }

    /** The comparison text: leading `--` lines dropped, the rest trimmed. */
    private fun body(text: String): String =
        text
            .lines()
            .dropWhile { it.startsWith("--") }
            .joinToString("\n")
            .trim()

    private fun version(name: String): Long = name.removePrefix("V").substringBefore("__").toLong()

    private companion object {
        /** `V202611011100__outbox_testmode.sql`, the highest on main when INV-T-18451 started. */
        const val PREVIOUS_HIGHEST = 202611011100L
    }
}
