package com.dynokore.platform.serviceruntime.testing

import com.dynokore.platform.serviceruntime.jobs.JobsHarness

/**
 * TEMPORARY, to be deleted with the last test that uses it: plain-SQL reads over the admin login for the integration tests
 * that are not yet converted to TransactionRunner and jOOQ (INV-Q-5119). Every user of this file is a gate finding that
 * the MR lists; none is exempt.
 */
fun JobsHarness.legacyRows(
    sql: String,
    vararg args: Any,
): List<Map<String, Any?>> =
    fixture.adminDataSource().connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val meta = rows.metaData
                buildList {
                    while (rows.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rows.getObject(it) })
                    }
                }
            }
        }
    }
