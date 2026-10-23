package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.catalogue.DeploymentForm

/**
 * Reads the scope-item table `agent-scope-items.tsv` (INV-Q-8874 A): the scope item each permission of an agent needs
 * (INV-REQ-913 behaviour 4). It fails closed: a permission that is not a key of the catalogue's role-by-permission
 * table, an item that is not exactly one of the three, a repeated permission, a row of the wrong width or a file
 * with no row stops the start-up with the line named. Keys and items are compared exactly, so a look-alike is unknown.
 */
object AgentScopeItemTable {
    const val RESOURCE = "agent-scope-items.tsv"
    private val HEADER = listOf("permission", "item", "basis")

    fun production(table: CatalogueTable = CatalogueTable.PUBLISHED): ScopeItemRequirements {
        val stream =
            checkNotNull(AgentScopeItemTable::class.java.getResourceAsStream("/$RESOURCE")) { "$RESOURCE is missing" }
        return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }, table)
    }

    fun parse(
        text: String,
        table: CatalogueTable = CatalogueTable.PUBLISHED,
    ): ScopeItemRequirements {
        val known = DeploymentForm.entries.flatMapTo(HashSet()) { form -> table.cells(form).values.flatMap { it.keys } }
        val lines = text.split("\n").filter { it.isNotEmpty() }
        check(
            lines.firstOrNull()?.split("\t") == HEADER,
        ) { "$RESOURCE: the header must be ${HEADER.joinToString("\t")}" }
        check(lines.size > 1) { "$RESOURCE: the file holds no row" }
        val rows = LinkedHashMap<Permission, ScopeItem>()
        lines.drop(1).forEachIndexed { index, line ->
            val at = "$RESOURCE row ${index + 1}"
            val cells = line.split("\t")
            check(cells.size == HEADER.size && cells[2].isNotBlank()) { "$at: expected permission, item and basis" }
            check(cells[0] in known) { "$at: '${cells[0]}' is not a permission key of the catalogue" }
            val item =
                checkNotNull(ScopeItem.entries.firstOrNull { it.name == cells[1] }) {
                    "$at: '${cells[1]}' is not a scope item"
                }
            val permission = Permission(cells[0])
            check(rows.put(permission, item) == null) { "$at: '${cells[0]}' is listed twice" }
        }
        return ScopeItemRequirements(rows)
    }
}
