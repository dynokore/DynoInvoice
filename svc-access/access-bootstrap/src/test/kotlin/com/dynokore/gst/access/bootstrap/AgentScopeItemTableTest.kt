package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RolePermissions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** INV-Q-8874 A: the file `agent-scope-items.tsv` and the loader that stops the service at start on a bad table. */
class AgentScopeItemTableTest {
    private val header = "permission\titem\tbasis\n"
    private val row = "submission.replay\tSUBMIT_INVOICE_DATA\tFR-AF.34 \"replay\"\n"

    private fun raw(): String =
        checkNotNull(javaClass.getResourceAsStream("/agent-scope-items.tsv")).bufferedReader().use { it.readText() }

    private fun rejects(text: String) =
        assertThatThrownBy {
            AgentScopeItemTable.parse(text)
        }.isInstanceOf(IllegalStateException::class.java)

    @Test
    fun `the file holds exactly the fourteen rows and each basis names FR-AF_34 or FR-AB_16`() {
        val rows =
            raw()
                .split("\n")
                .filter { it.isNotEmpty() }
                .drop(1)
                .map { it.split("\t") }
        assertThat(rows.map { it[0] to it[1] }).containsExactlyInAnyOrder(
            "masterdata-exception.resolve" to "SUBMIT_INVOICE_DATA",
            "period-readiness.run" to "SUBMIT_INVOICE_DATA",
            "submission.replay" to "SUBMIT_INVOICE_DATA",
            "mapping-pack.author" to "SUBMIT_INVOICE_DATA",
            "transaction.ingest" to "SUBMIT_INVOICE_DATA",
            "mapping-pack.approve" to "MAPPING_APPROVAL",
            "filing.confirm" to "FILE_GST_RETURN",
            "filed-return.upload" to "FILE_GST_RETURN",
            "reconciliation.run" to "FILE_GST_RETURN",
            "reconciliation.annotate" to "FILE_GST_RETURN",
            "reconciliation.attest" to "FILE_GST_RETURN",
            "submission.view-status" to "SUBMIT_INVOICE_DATA",
            "doc.view.content" to "SUBMIT_INVOICE_DATA",
            "period-readiness.view" to "SUBMIT_INVOICE_DATA",
        )
        assertThat(rows).allSatisfy { assertThat(it[2]).matches("FR-A[FB]\\.(34|16) .*") }
    }

    @Test
    fun `the production table maps the fourteen keys and nothing else, look-alike keys are unknown`() {
        val table = AgentScopeItemTable.production()
        assertThat(table.itemFor(Permission("submission.replay"))).isEqualTo(ScopeItem.SUBMIT_INVOICE_DATA)
        assertThat(table.itemFor(Permission("mapping-pack.approve"))).isEqualTo(ScopeItem.MAPPING_APPROVAL)
        listOf("submission.replays", "Submission.replay", "submission.replay ").forEach { key ->
            assertThat(runCatching { table.itemFor(Permission(key)) }.getOrNull()).isNull()
        }
        listOf(
            "validation.override",
            "audit-pack.export",
            "mapping-pack.activate",
        ).forEach { assertThat(table.itemFor(Permission(it))).describedAs(it).isNull() }
    }

    @Test
    fun `every key of the file is a key of the catalogue table`() {
        val known =
            DeploymentForm.entries
                .flatMap {
                    RolePermissions.cells(it).values.flatMap { cell ->
                        cell.keys
                    }
                }.toSet()
        raw()
            .split("\n")
            .filter { it.isNotEmpty() }
            .drop(1)
            .forEach { assertThat(known).contains(it.split("\t")[0]) }
    }

    @Test
    fun `a table stops the service at start for an unknown key, a wrong item, a repeat or no row`() {
        rejects(header + "submission.replays\tSUBMIT_INVOICE_DATA\tb\n")
        rejects(header + "submission.replay \tSUBMIT_INVOICE_DATA\tb\n")
        rejects(header + "Submission.replay\tSUBMIT_INVOICE_DATA\tb\n")
        rejects(header + "submission.replay\tsubmit_invoice_data\tb\n")
        rejects(header + "submission.replay\tSUBMIT_INVOICE_DATA, FILE_GST_RETURN\tb\n")
        rejects(header + row + row)
        rejects(header + row.trimEnd() + "\n" + "submission.replay\tMAPPING_APPROVAL\tb\n")
        rejects(header + "audit-pack.exports\tSUBMIT_INVOICE_DATA\tb\n")
        rejects(header)
        rejects("")
        rejects(header + "submission.replay\tSUBMIT_INVOICE_DATA\n")
        rejects(header + "submission.replay\tSUBMIT_INVOICE_DATA\t\n")
        assertThat(AgentScopeItemTable.parse(header + row)).isInstanceOf(ScopeItemRequirements::class.java)
    }
}
