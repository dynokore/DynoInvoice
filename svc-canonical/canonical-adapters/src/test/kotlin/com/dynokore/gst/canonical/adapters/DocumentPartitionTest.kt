package com.dynokore.gst.canonical.adapters

import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-REQ-640 and INV-REQ-641: partitioning of both tables and the indexes of `document`. */
class DocumentPartitionTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()

    /** The strategy and key columns of a partitioned table, read from the catalogue through jOOQ. */
    private fun strategy(table: String): Pair<String, List<String>> =
        CanonicalDb.superuser().use { connection ->
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            val c = DSL.table(DSL.name("pg_catalog", "pg_class")).`as`("c")
            val p = DSL.table(DSL.name("pg_catalog", "pg_partitioned_table")).`as`("p")
            val rows =
                dsl
                    .select(
                        DSL.field(DSL.name("p", "partstrat"), String::class.java),
                        DSL.field(
                            "(select string_agg(a.attname, ',' order by k.ord) from unnest(p.partattrs::int2[]) " +
                                "with ordinality k(attnum, ord) join pg_attribute a on a.attrelid = p.partrelid " +
                                "and a.attnum = k.attnum)",
                            String::class.java,
                        ),
                    ).from(c)
                    .join(p)
                    .on(DSL.field(DSL.name("p", "partrelid")).eq(DSL.field(DSL.name("c", "oid"))))
                    .where(DSL.field(DSL.name("c", "oid")).eq(DSL.field("?::regclass", Any::class.java, table)))
                    .fetch()
            rows.single().let { it.value1()!! to it.value2()!!.split(',') }
        }

    private fun subpartitionStrategies(table: String): Set<Pair<String, List<String>>> =
        DocumentSql
            .rows(
                "select child.oid::regclass::text from pg_inherits i join pg_class child on child.oid = i.inhrelid " +
                    "where i.inhparent = ?::regclass and child.relkind = 'p'",
                table,
            ).map { strategy(it[0]!!) }
            .toSet()

    private fun holder(
        table: String,
        doc: UUID,
        extra: String = "",
    ) = DocumentSql.one("select tableoid::regclass::text from canonical.$table where doc_uuid = ? $extra", doc)

    @Test
    fun `INV-AC-1809 document and document_version are range partitioned on partition_date and hash sub-partitioned`() {
        listOf("canonical.document", "canonical.document_version").forEach {
            strategy(it) shouldBe ("r" to listOf("partition_date"))
            subpartitionStrategies(it) shouldBe setOf("h" to listOf("entity_id"))
        }
    }

    @Test
    fun `INV-AC-1809 a partition date outside every created range goes to the default partition`() {
        val document = DocumentKit.document(tenant, entity, LocalDate.of(1999, 1, 1))
        kit.insert(document)
        holder("document", document.docUuid) shouldBe "canonical.document_default"
        holder("document_version", document.docUuid) shouldBe "canonical.document_version_default"
        val inRange = DocumentKit.document(tenant, entity, LocalDate.of(2026, 6, 1))
        kit.insert(inRange)
        holder("document", inRange.docUuid)!!.startsWith("canonical.document_y2026_h") shouldBe true
    }

    @Test
    fun `INV-AC-1809 an amendment into another period moves no row`() {
        val document = DocumentKit.document(tenant, entity, LocalDate.of(2026, 3, 15))
        kit.insert(document)
        val documentBefore = holder("document", document.docUuid)
        val versionBefore = holder("document_version", document.docUuid, "and version = 1")
        kit.asTenant(tenant) {
            kit.versions.createVersion(document.docUuid, 1, DocumentKit.version(LocalDate.of(2027, 2, 1)))
        } shouldBe UpdateOutcome.Updated(2)
        DocumentSql.documentColumn("transaction_date", document.docUuid) shouldBe "2027-02-01"
        DocumentSql.documentColumn("partition_date", document.docUuid) shouldBe "2026-03-15"
        holder("document", document.docUuid) shouldBe documentBefore
        holder("document_version", document.docUuid, "and version = 1") shouldBe versionBefore
        holder("document_version", document.docUuid, "and version = 2") shouldBe versionBefore
    }

    @Test
    fun `INV-AC-1810 the indexes of document are doc_uuid and entity with document number`() {
        val indexes =
            DocumentSql
                .rows("select indexdef from pg_indexes where schemaname = 'canonical' and tablename = 'document'")
                .map { it[0]!! }
        indexes.any { "UNIQUE INDEX document_pkey" in it && "(doc_uuid, partition_date, entity_id)" in it } shouldBe
            true
        indexes.any { "INDEX document_entity_document_number" in it && "(entity_id, document_number)" in it } shouldBe
            true
    }

    @Test
    fun `INV-AC-1810 the lookups by entity and document number and by doc_uuid use an index on 10000 documents`() {
        val bulkEntity = UUID.randomUUID()
        val bulkTenant = UUID.randomUUID()
        // 10 statements of 1000 rows: each commits alone, so the per-row advisory locks of the insert trigger stay few
        repeat(BATCHES) { batch ->
            DocumentSql.execute(
                "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                    "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, " +
                    "current_version, state) " +
                    "select gen_random_uuid(), '$bulkTenant', '$bulkEntity', 'SALE', 380, 'N-$batch-' || g, " +
                    "'issuer', 2026, " +
                    "date '2026-01-01' + (g % 700), date '2026-01-01' + (g % 700), 1, 'READY' " +
                    "from generate_series(1, $PER_BATCH) g",
            )
        }
        DocumentSql.one("select count(*) from canonical.document where tenant_id = ?", bulkTenant) shouldBe "10000"
        DocumentSql.execute("analyze canonical.document")
        val parentIndexed = indexNamesOf("document_entity_document_number")
        val lookup =
            plan("select 1 from canonical.document where entity_id = '$bulkEntity' and document_number = 'N-3-17'")
        // an empty or one-row partition may be read in full; a partition holding the generated rows may not
        val loaded =
            DocumentSql
                .rows(
                    "select distinct c.relname from canonical.document d join pg_class c on c.oid = d.tableoid " +
                        "where d.tenant_id = ?",
                    bulkTenant,
                ).map { it[0]!! }
        (loaded.size >= 2) shouldBe true
        withClue(lookup) { loaded.none { lookup.contains("Seq Scan on $it ") } shouldBe true }
        usedIndexes(lookup).isNotEmpty() shouldBe true
        parentIndexed.containsAll(usedIndexes(lookup)) shouldBe true
        val byUuid = plan("select 1 from canonical.document where doc_uuid = '${UUID.randomUUID()}'")
        withClue(byUuid) { loaded.none { byUuid.contains("Seq Scan on $it ") } shouldBe true }
        indexNamesOf("document_pkey").containsAll(usedIndexes(byUuid)) shouldBe true
    }

    /** The index [parent] and its partition indexes, by name. */
    private fun indexNamesOf(parent: String): Set<String> =
        DocumentSql
            .rows(
                "with recursive t(oid) as (select ?::regclass::oid union all " +
                    "select i.inhrelid from pg_inherits i join t on i.inhparent = t.oid) " +
                    "select c.relname from t join pg_class c on c.oid = t.oid",
                "canonical.$parent",
            ).map { it[0]!! }
            .toSet()

    private fun plan(sql: String): String = DocumentSql.rows("explain $sql").joinToString("\n") { it[0]!! }

    private fun usedIndexes(plan: String): Set<String> =
        Regex("""Index (?:Only )?Scan using (\S+) on""").findAll(plan).map { it.groupValues[1] }.toSet()

    private companion object {
        const val BATCHES = 10
        const val PER_BATCH = 1000
    }
}
