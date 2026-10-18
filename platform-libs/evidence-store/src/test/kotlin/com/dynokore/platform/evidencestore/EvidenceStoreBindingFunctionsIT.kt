package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * The fixture installs the binding key table and functions in its schema before its first policy (INV-Q-6679), so a
 * policy that reads `bound_tenant()` can be created there. Three samples, as the runtime login, on a real PostgreSQL.
 */
@Tag("integration")
class EvidenceStoreBindingFunctionsIT {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the fixture schema holds working binding functions`(image: String) {
        EvidenceStorePostgresFixture(image).use { fixture ->
            val probe =
                TenancyProbe.connect(
                    fixture.jdbcUrl(),
                    EvidenceStorePostgresFixture.APP_USER,
                    EvidenceStorePostgresFixture.APP_PASSWORD,
                )
            val bound = "select ${EvidenceStorePostgresFixture.SCHEMA}.bound_tenant()"
            val tenant = TenantId(UUID.randomUUID())

            // a signed binding gives the tenant
            assertEquals(tenant.value, probe.inTenant(tenant) { fetchValue(bound) })
            // no binding gives NULL
            assertNull(probe.unbound { fetchValue(bound) })
            // a schema without the functions raises "undefined function"
            val refused = assertThrows<DataAccessException> { probe.fetchValue("select public.bound_tenant()") }
            assertEquals("42883", refused.sqlState())
        }
    }
}
