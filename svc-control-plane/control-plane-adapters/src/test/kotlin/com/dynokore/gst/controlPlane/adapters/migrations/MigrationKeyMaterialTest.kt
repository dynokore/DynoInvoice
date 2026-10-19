package com.dynokore.gst.controlPlane.adapters.migrations

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Signing keys are read through the SecretStore binding by reference only; the vault key is never in the schema
 * (INV-T-4984, STK-29, CS-24). Deny by default: a migration may hold SQL, comments and short identifiers;
 * any PEM block,
 * long base64 or hex run, key-looking literal, or a column that can hold a key (not a `_ref`) is a finding.
 */
class MigrationKeyMaterialTest {
    private val dir = File(System.getProperty("gst.migrations.dir"))

    @Test
    fun `no migration holds key material or a column that can hold a key`() {
        val files = dir.listFiles { f -> f.extension == "sql" }.orEmpty().toList()
        (files.size >= 2) shouldBe true
        files.flatMap { KeyMaterial.findings(it.name, it.readText()) }.shouldBeEmpty()
    }

    @Test
    fun `a planted key is found in every disguise`() {
        listOf(
            "-- -----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----",
            "insert into t values ('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');",
            "insert into t values ('0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef');",
            "create table t (signing_key text);",
            "create table t (private_key bytea);",
            "create table t (key_material bytea);",
            "create table t (secret text);",
            "create table t (password text);",
            "comment on table t is 'AKIAIOSFODNN7EXAMPLE';",
            "create table t (cosign_key text);",
        ).forEach { sql ->
            KeyMaterial.findings("planted.sql", sql).isEmpty() shouldBe false
        }
    }

    @Test
    fun `the key reference column and a content hash are not findings`() {
        // the hash column is checked by a pattern, not by a literal
        KeyMaterial
            .findings(
                "ok.sql",
                "create table t (signing_key_ref text check (length(signing_key_ref) <= 64));",
            ).shouldBeEmpty()
        KeyMaterial.findings("ok.sql", "check (pack_content_hash ~ '^[0-9a-f]{64}\$')").shouldBeEmpty()
    }
}

internal object KeyMaterial {
    private val PEM = Regex("""-----BEGIN [A-Z ]*(?:KEY|CERTIFICATE)-----""", RegexOption.IGNORE_CASE)
    private val LONG_BASE64 = Regex("""[A-Za-z0-9+/=_-]{40,}""")
    private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val AWS = Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""")
    private const val KEY_WORDS =
        "private_key|signing_key|key_material|cosign_key|secret|password|passwd|credential|token"
    private val KEY_COLUMN = Regex("""(?i)\b(\w*(?:$KEY_WORDS)\w*)\s+(?:text|bytea|varchar|char|jsonb|json)\b""")

    fun findings(
        file: String,
        sql: String,
    ): List<String> {
        val out = ArrayList<String>()
        if (PEM.containsMatchIn(sql)) out += "$file: PEM block"
        LONG_BASE64.findAll(sql).forEach { out += "$file: long base64-like run '${it.value.take(12)}...'" }
        LONG_HEX.findAll(sql).forEach { out += "$file: long hex run '${it.value.take(12)}...'" }
        if (AWS.containsMatchIn(sql)) out += "$file: access key id"
        KEY_COLUMN.findAll(sql).map { it.groupValues[1] }.filter { !it.endsWith("_ref") }.forEach {
            out +=
                "$file: column '$it' can hold a secret"
        }
        return out
    }
}
