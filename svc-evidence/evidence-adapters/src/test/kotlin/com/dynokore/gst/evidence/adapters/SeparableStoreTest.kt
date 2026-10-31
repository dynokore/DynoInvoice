package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.jooq.DefaultCatalog
import io.kotest.matchers.shouldBe
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.Table
import org.jooq.TableField
import org.jooq.TableOptions
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val HASH_LENGTH = 64

/**
 * INV-AC-7872 and INV-REQ-636: dropping the operational schema leaves every row of every `evidence` table retrievable
 * and equal to what was written. The scratch schema `operational_probe` stands for an operational store.
 */
class SeparableStoreTest {
    private val tenant = UUID.randomUUID()
    private val written =
        DefaultCatalog.DEFAULT_CATALOG.EVIDENCE.tables
            .filter { it.tableType == TableOptions.TableType.TABLE }

    private val foreign =
        """
        create schema operational_probe;
        create table operational_probe.item (id uuid primary key, label text not null);
        insert into operational_probe.item values ('00000000-0000-0000-0000-000000000001', 'one'),
            ('00000000-0000-0000-0000-000000000002', 'two');
        """.trimIndent()

    private fun <T> bound(
        url: String,
        block: (Connection) -> T,
    ): T =
        DriverManager.getConnection(url, EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            block(connection).also { connection.commit() }
        }

    /** The columns the generator constrains to a closed set of texts (check constraints of the consumer side). */
    private val constrained: Map<String, Any> =
        mapOf(
            "event_dead_letter.status" to "OPEN",
            "consumer_replay.mode" to "SCOPE",
            "case_register.case_kind" to "SECURITY",
            "case_register.status" to "OPEN",
            "idempotency_key.status" to 200,
            "period_digest.digest" to "sha256:" + "a".repeat(HASH_LENGTH),
            "period_digest_chain.evidence_type" to "PAYLOAD",
            "period_digest_chain.tail_hash" to "a".repeat(HASH_LENGTH),
            "integrity_run.result" to "VERIFIED",
            "integrity_run.cause" to "VERIFICATION",
            "integrity_run.period_digest" to "sha256:" + "a".repeat(HASH_LENGTH),
            "integrity_failure.evidence_type" to "PAYLOAD",
            "integrity_failure.kind" to "BROKEN_CHAIN", // the sampled failing_seq is set, which only this kind allows
        )

    /** Columns left unset: a SECURITY case has no subject (the shape check of `case_register`). */
    private val unset = setOf("case_register.subject_ref")

    private val byType: Map<Class<*>, (TableField<*, *>) -> Any> =
        mapOf(
            UUID::class.java to { f -> if (f.name == "tenant_id") tenant else UUID.randomUUID() },
            String::class.java to { f -> "evidence-${f.name}" },
            Long::class.javaObjectType to { _ -> 42L },
            Long::class.javaPrimitiveType!! to { _ -> 42L },
            Int::class.javaObjectType to { _ -> 7 },
            Int::class.javaPrimitiveType!! to { _ -> 7 },
            Boolean::class.javaObjectType to { _ -> true },
            Boolean::class.javaPrimitiveType!! to { _ -> true },
            OffsetDateTime::class.java to
                { _ -> OffsetDateTime.of(2026, 3, 20, 10, 15, 30, 123_000_000, ZoneOffset.UTC) },
            Array<String>::class.java to { f -> arrayOf("evidence-${f.name}") },
            Array<UUID>::class.java to { _ -> arrayOf(UUID.randomUUID()) },
            ByteArray::class.java to { f -> "evidence-${f.name}".toByteArray() },
            java.time.LocalDate::class.java to { _ -> java.time.LocalDate.of(2026, 3, 20) },
            org.jooq.JSONB::class.java to { _ -> org.jooq.JSONB.valueOf("""{"payload": "evidence"}""") },
        )

    /** A value for a column from its Java type; a type this file does not know fails the test, never skips it. */
    private fun sample(field: TableField<*, *>): Any =
        constrained["${field.table?.name}.${field.name}"]
            ?: byType[field.dataType.type]?.invoke(field)
            ?: error(
                "no sample value for ${field.name} of type ${field.dataType.type}; add one",
            )

    private fun comparable(value: Any?): Any? =
        when (value) {
            is OffsetDateTime -> value.toInstant()
            is ByteArray -> value.toList()
            is Array<*> -> value.toList()
            else -> value
        }

    private fun fields(record: Record): Map<String, Any?> =
        record.fields().associate {
            it.name to
                comparable(record[it])
        }

    private fun tablesInCatalogue(url: String): Set<String> =
        DriverManager.getConnection(url, EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { connection ->
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            dsl
                .select(DSL.field(DSL.name("relname"), String::class.java))
                .from(DSL.table(DSL.name("pg_catalog", "pg_class")))
                .join(DSL.table(DSL.name("pg_catalog", "pg_namespace")))
                .on(DSL.field(DSL.name("pg_class", "relnamespace")).eq(DSL.field(DSL.name("pg_namespace", "oid"))))
                .where(DSL.field(DSL.name("nspname"), String::class.java).eq(EvidenceDb.SCHEMA))
                .and(DSL.field(DSL.name("relkind"), String::class.java).`in`("r", "p"))
                .fetch(DSL.field(DSL.name("relname"), String::class.java))
                .toSet() - setOf("flyway_schema_history", "binding_key")
        }

    @Test
    fun `INV-AC-7872 dropping the operational schema leaves every row of every evidence table retrievable and equal`() {
        EvidenceDb.withScratchDatabase(foreign) { url ->
            // the number of tables written equals the number of tables the migrations create in schema evidence
            written.map { it.name }.toSet() shouldBe tablesInCatalogue(url)
            val before = writeEveryTable(url)
            val operational =
                EvidenceDb.superuserOf(url).use { connection ->
                    DSL
                        .using(
                            connection,
                            SQLDialect.POSTGRES,
                        ).fetchCount(DSL.table(DSL.name("operational_probe", "item")))
                }
            operational shouldBe 2
            EvidenceDb.superuserOf(url).use { connection ->
                connection.createStatement().use {
                    it.execute("truncate operational_probe.item")
                    it.execute("drop schema operational_probe cascade")
                }
            }
            val after =
                bound(url) { connection ->
                    val dsl = DSL.using(connection, SQLDialect.POSTGRES)
                    written.associate { table -> table.name to dsl.selectFrom(table).fetch().toList() }
                }
            written.forEach { table ->
                val rows = after.getValue(table.name)
                rows.size shouldBe 1
                fields(rows.single()) shouldBe fields(before.getValue(table.name))
            }
            after.keys shouldBe written.map { it.name }.toSet()
        }
    }

    /** One row in every table: by the runtime login where it may insert, else by the owner of the schema. */
    private fun writeEveryTable(url: String): Map<String, Record> =
        bound(url) { connection ->
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            val identity = identityColumns(dsl)
            EvidenceDb.superuserOf(url).use { superuser ->
                val owner = DSL.using(superuser, SQLDialect.POSTGRES)
                // the dead letters are written through a sys function, never by the runtime login itself
                written.associate { table ->
                    table.name to insertOne(if (runtimeMayInsert(dsl, table)) dsl else owner, table, identity)
                }
            }
        }

    private fun runtimeMayInsert(
        dsl: org.jooq.DSLContext,
        table: Table<*>,
    ): Boolean =
        dsl.fetchValue(
            "select has_table_privilege(?, ?, 'INSERT')",
            EvidenceDb.RUNTIME,
            "${EvidenceDb.SCHEMA}.${table.name}",
        ) as Boolean

    /** `table.column` of the identity columns, from the catalogue (the generated code does not mark them). */
    private fun identityColumns(dsl: org.jooq.DSLContext): Set<String> =
        dsl
            .select(
                DSL.field(DSL.name("table_name"), String::class.java),
                DSL.field(DSL.name("column_name"), String::class.java),
            ).from(DSL.table(DSL.name("information_schema", "columns")))
            .where(DSL.field(DSL.name("table_schema"), String::class.java).eq(EvidenceDb.SCHEMA))
            .and(DSL.field(DSL.name("is_identity"), String::class.java).eq("YES"))
            .fetch { "${it.value1()}.${it.value2()}" }
            .toSet()

    private fun <R : Record> insertOne(
        dsl: org.jooq.DSLContext,
        table: Table<R>,
        identity: Set<String>,
    ): R {
        val record = dsl.newRecord(table)
        table.fields().filterIsInstance<TableField<R, *>>().forEach { field ->
            val name = "${table.name}.${field.name}"
            if (name !in identity && name !in unset) setValue(record, field)
        }
        return dsl
            .insertInto(table)
            .set(record)
            .returning()
            .fetchSingle()
    }

    private fun <R : Record, T : Any> setValue(
        record: R,
        field: TableField<R, T>,
    ) {
        @Suppress("UNCHECKED_CAST")
        record.set(field, sample(field) as T)
    }
}
