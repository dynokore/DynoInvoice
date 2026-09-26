package com.dynokore.platform.tenancykernel

import java.sql.Connection
import java.util.UUID

/**
 * The tenant-probe pattern (TPL-14, CS-19): reusable low-level JDBC helpers a
 * test uses directly against [PostgresProbeFixture]'s `appDataSource`, without
 * needing [TransactionRunner] itself to be the thing under test.
 */
object ProbeTable {
    fun insert(
        connection: Connection,
        row: ProbeRow,
    ) {
        connection.prepareStatement("insert into tenancy_kernel_test.probe (id, tenant_id, value) values (?, ?, ?)").use { statement ->
            statement.setObject(1, row.id)
            statement.setObject(2, row.tenantId.value)
            statement.setString(3, row.value)
            statement.executeUpdate()
        }
    }

    fun selectAll(connection: Connection): List<ProbeRow> = connection.createStatement().use { statement ->
        statement.executeQuery("select id, tenant_id, value from tenancy_kernel_test.probe order by value").use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        ProbeRow(
                            id = rs.getObject("id", UUID::class.java),
                            tenantId = TenantId(rs.getObject("tenant_id", UUID::class.java)),
                            value = rs.getString("value"),
                        ),
                    )
                }
            }
        }
    }
}
