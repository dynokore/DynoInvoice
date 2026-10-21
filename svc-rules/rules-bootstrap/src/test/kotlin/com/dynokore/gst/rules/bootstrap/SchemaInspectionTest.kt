package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.bootstrap.SchemaInspection.Column
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Grant
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Table
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/** The probes of INV-AC-2182's inspection: each look-alike or violation must produce a finding. */
class SchemaInspectionTest {
    private val libraries = setOf("outbox")
    private val holder = Table("rules", "pack_artefact")
    private val registry = listOf(Table("rules", "pack_registry"), Table("rules", "pack_entry_source"))
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
