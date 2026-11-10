package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.bootstrap.SchemaInspection.Column
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Grant
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Table
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.platform.tenancyjooq.TenancyProbe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.UUID

/** The probes of INV-AC-2182's inspection: each look-alike or violation must produce a finding. */
class SchemaInspectionTest {
    private val libraries = setOf("outbox")
    private val holder = Table("rules", "pack_artefact")
    private val registry =
        listOf(
            Table("rules", "pack_registry"),
            Table("rules", "pack_entry_source"),
            Table("rules", "mapping_entry_acceptance"),
            Table("rules", "mapping_pack_approval"),
            Table("rules", "dry_run_delta"),
            Table("rules", "mapping_import_record"),
        )
    private val holderColumns = SchemaInspection.HOLDER_COLUMNS.map { Column(holder, it, "text") }
    private val good = registry + holder + Table("rules", "outbox")

    private fun findings(
        tables: List<Table> = good,
        columns: List<Column> = holderColumns,
    ) = SchemaInspection.tableFindings(tables, columns, libraries)

    @Test
    fun `INV-AC-2182 the expected tables and columns have no finding`() {
        findings().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - look-alike holder tables do not count as the holder copy`() {
        findings(registry + Table("rules", "pack_artefacts")).shouldNotBeEmpty()
        findings(registry + Table("rules", "pack_artifact")).shouldNotBeEmpty()
        findings(registry + Table("other", "pack_artefact")).shouldNotBeEmpty()
        findings(registry + Table("rules", "PACK_ARTEFACT")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - a table that is not classified fails`() {
        findings(good + Table("rules", "pack_artefact_copy")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - the holder copy has exactly the kernel columns and none named version`() {
        findings(columns = holderColumns + Column(holder, "version", "text")).shouldNotBeEmpty()
        findings(columns = holderColumns.filter { it.name != "pack_version" } + Column(holder, "version", "text"))
            .shouldNotBeEmpty()
        findings(columns = holderColumns.filter { it.name != "signature" }).shouldNotBeEmpty()
        findings(columns = holderColumns + Column(holder, "id", "uuid")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - a document column in a metadata table fails, in the holder copy it does not`() {
        val registryTable = registry.first()
        for (type in listOf("json", "jsonb", "bytea")) {
            findings(columns = holderColumns + Column(registryTable, "extra", type)).shouldNotBeEmpty()
            findings(columns = holderColumns + Column(registry.last(), "extra", type)).shouldNotBeEmpty()
        }
        findings(columns = holderColumns.map { it.copy(type = "jsonb") }).shouldBeEmpty()
    }

    @Test
    fun `the registry tables are exactly the six names`() {
        SchemaInspection.REGISTRY_TABLES shouldBe
            setOf(
                "pack_registry",
                "pack_entry_source",
                "mapping_entry_acceptance",
                "mapping_pack_approval",
                "dry_run_delta",
                "mapping_import_record",
            )
    }

    @Test
    fun `probe - an unclassified look-alike of a new table fails`() {
        findings(good + Table("rules", "mapping_entry_acceptances")).shouldNotBeEmpty()
        findings(good + Table("rules", "dry_run_deltas")).shouldNotBeEmpty()
        findings(good + Table("rules", "mapping_pack_approvals")).shouldNotBeEmpty()
    }

    @Test
    fun `probe - a jsonb column on dry_run_delta and a bytea column on mapping_pack_approval fail`() {
        findings(columns = holderColumns + Column(Table("rules", "dry_run_delta"), "per_document", "jsonb"))
            .shouldNotBeEmpty()
        findings(columns = holderColumns + Column(Table("rules", "mapping_pack_approval"), "signature", "bytea"))
            .shouldNotBeEmpty()
        findings(columns = holderColumns + Column(Table("rules", "mapping_entry_acceptance"), "doc", "json"))
            .shouldNotBeEmpty()
    }

    @Test
    fun `probe - the same table name in another schema is not classified`() {
        findings(good + Table("other", "dry_run_delta")).shouldBeEmpty() // another schema is not this inspection's
        findings(good.filter { it.name != "dry_run_delta" } + Table("other", "dry_run_delta")).shouldBeEmpty()
        findings(good + Table("rules", "dry_run_delta_other")).shouldNotBeEmpty()
    }

    @Test
    fun `probe - the holder copy is unchanged by the new tables`() {
        findings(columns = holderColumns.filter { it.name != "contents" }).shouldNotBeEmpty()
        findings(columns = holderColumns).shouldBeEmpty()
    }

    private val runtime = listOf(Grant("rules_runtime", "INSERT"), Grant("rules_runtime", "SELECT"))
    private val owner = listOf(Grant("rules_migrate", "UPDATE"), Grant("rules_migrate", "DELETE"))

    private val sys = Grant("rules_sys", "INSERT")
    private val expected = runtime + sys

    private fun grants(all: List<Grant>) = SchemaInspection.grantFindings(all)

    @Test
    fun `INV-AC-2182 the three expected grants and an owner that holds anything have no finding`() {
        grants(expected + owner).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - planted ACLs each fail`() {
        grants(expected + Grant("rules_sys", "SELECT")).shouldNotBeEmpty()
        grants(runtime + Grant("rules_sys", "INSERT", grantable = true)).shouldNotBeEmpty()
        grants(expected + Grant("rules_runtime", "UPDATE")).shouldNotBeEmpty()
        grants(listOf(Grant("rules_runtime", "SELECT"), sys)).shouldNotBeEmpty()
        grants(expected + Grant("PUBLIC", "SELECT")).shouldNotBeEmpty()
        grants(expected + Grant("rules_sys2", "INSERT")).shouldNotBeEmpty()
        grants(expected + Grant("rules_runtime", "SELECT", column = "contents")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - a missing triple, a grant option and the forbidden privileges fail`() {
        grants(runtime).shouldNotBeEmpty()
        grants(listOf(Grant("rules_runtime", "INSERT", grantable = true), Grant("rules_runtime", "SELECT"), sys))
            .shouldNotBeEmpty()
        for (privilege in listOf("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
            grants(expected + Grant("rules_runtime", privilege)).shouldNotBeEmpty()
            grants(expected + Grant("rules_sys", privilege)).shouldNotBeEmpty()
        }
        grants(emptyList()).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-2182 probe - rules_sys with login fails`() {
        SchemaInspection.loginFindings(false).shouldBeEmpty()
        SchemaInspection.loginFindings(true).shouldNotBeEmpty()
        SchemaInspection.loginFindings(null).shouldNotBeEmpty()
    }
}

private const val SCHEME_TAG_SCHEMA_PATTERN = "^[A-Z][A-Z0-9_]{1,39}$"
private const val SCHEME_TAG_MAX = 40

/**
 * The `scheme_tag` column of the draft entry table, on the database of the integration tests: the CHECK and the `tag`
 * pattern of `scenario-pack-handlers.schema.json` give the same verdict for every probe (INV-REQ-2444).
 */
class SchemeTagColumnTest {
    private val probe: TenancyProbe

    init {
        PostgresRules.register(
            DynamicPropertyRegistry { _, _ -> },
        )
        probe =
            TenancyProbe
                .connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    }

    private fun seed(version: String) {
        probe.inTenant(TENANT_A) {
            fetchValue(
                "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, " +
                    "status, min_engine_version, registered_by, label, entity_ref) " +
                    "values (?::uuid, ?::uuid, ?, 'MAPPING', ?, " +
                    "'TENANT_MAPPING', 'DRAFT', '1.0.0', 'tenancy-probe', ?, 'E-1') returning id",
                UUID.randomUUID().toString(),
                TENANT_A.toString(),
                "MAP-$version",
                version,
                version,
            )
        }
    }

    private fun store(
        version: String,
        entry: String,
        tag: String?,
    ) = probe.inTenant(TENANT_A) {
        fetchValue(
            "insert into rules.pack_entry_source (id, tenant_id, pack_type, pack_version, entry_id, source_class, " +
                "internal_tax_code, iras_code, scheme_tag) values (?::uuid, ?::uuid, 'MAPPING', ?, ?, 'PRIMARY', " +
                "?, 'SR', ?) returning id",
            UUID.randomUUID().toString(),
            TENANT_A.toString(),
            version,
            entry,
            entry,
            tag,
        )
    }

    private fun stored(
        version: String,
        entry: String,
    ) = probe.inTenant(TENANT_A) {
        fetchValue(
            "select coalesce(scheme_tag, '<null>') from rules.pack_entry_source " +
                "where pack_version = ? and entry_id = ?",
            version,
            entry,
        )
    }

    @Test
    fun `INV-AC-16854 the scheme_tag check and the tag pattern of the scenario schema give the same verdict`() {
        val version = "st-${UUID.randomUUID().toString().take(8)}"
        seed(version)
        val schema = Regex(SCHEME_TAG_SCHEMA_PATTERN)
        val probes =
            mapOf(
                "A" to false, // one character
                "AB" to true,
                "aB" to false, // lower case
                "A".repeat(SCHEME_TAG_MAX) to true, // the longest tag
                "A".repeat(SCHEME_TAG_MAX + 1) to false, // 41 characters
                "A_1" to true,
                "1A" to false,
                "A-B" to false,
                "AB\n" to false, // a trailing newline does not slip past the anchor
            )
        probes.entries.forEachIndexed { index, (tag, accepted) ->
            schema.matches(tag) shouldBe accepted
            val entry = "E$index"
            if (accepted) {
                store(version, entry, tag)
                stored(version, entry) shouldBe tag
            } else {
                shouldThrow<Exception> { store(version, entry, tag) }
            }
        }
    }

    @Test
    fun `INV-AC-16854 an entry without a tag reads back null`() {
        val version = "st-${UUID.randomUUID().toString().take(8)}"
        seed(version)
        store(version, "NOTAG", null)
        stored(version, "NOTAG") shouldBe "<null>"
    }
}
