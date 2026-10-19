package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The `CREATE TABLE` and index part of [PackRegistryMigration]'s registry table DDL (split out of that object). */
internal object PackRegistryTableDdl {
    fun create(
        schema: String,
        table: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val t = SqlIdentifier.require(table, "table")
        val index = SqlIdentifier.require("${t}_resolution_idx", "resolution index")
        val lineageKey = SqlIdentifier.require("${t}_lineage_key", "lineage constraint")
        return """
            |CREATE TABLE $s.$t (
            |    id                 uuid PRIMARY KEY,
            |    pack_id            text NOT NULL,
            |    pack_type          text NOT NULL,
            |    version            text NOT NULL,
            |    tenant_id          uuid NOT NULL,
            |    effective_from     date NOT NULL,
            |    effective_to       date NULL,
            |    min_engine_version text NOT NULL,
            |    manifest           jsonb NOT NULL,
            |    checksums          jsonb NOT NULL,
            |    signature          bytea NOT NULL,
            |    contents           jsonb NOT NULL,
            |    trusted_anchor     text NOT NULL,
            |    registered_by      text NOT NULL CHECK (registered_by !~ '${BlankText.SQL_NOT_ACTOR}'),
            |    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
            |    CONSTRAINT $lineageKey UNIQUE (pack_type, tenant_id, version)
            |);
            |CREATE INDEX $index ON $s.$t (pack_type, tenant_id, effective_from);
            """.trimMargin()
    }
}
