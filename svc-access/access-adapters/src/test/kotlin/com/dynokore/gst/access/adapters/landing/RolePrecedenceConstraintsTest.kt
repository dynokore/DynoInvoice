package com.dynokore.gst.access.adapters.landing

import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** The check access-svc gives `setPolicy` for VP-73: catalogue roles, each once (INV-REQ-2756). */
class RolePrecedenceConstraintsTest {
    private val privateRoles = RoleCatalogue(Role.entries.toSet() - Role.PLATFORM_SUPPORT)
    private val constraints = RolePrecedenceConstraints(privateRoles, JsonMapper())
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val today = LocalDate.of(2027, 1, 1)

    private fun check(
        json: String?,
        key: String = "VP-73",
    ) = constraints.check(
        target = SettingTarget(tenant, ScopeRef(ScopeLevel.TENANT, tenant.toString()), null),
        key = VariationPointKey(key),
        selected = listOf(SelectedOption("TENANT_PRECEDENCE", json)),
        onDate = today,
    )

    @Test
    fun `a list of known roles is accepted`() {
        assertThat(check("[\"tax.approver\",\"tenant.admin\"]")).isEqualTo(ConstraintVerdict.Accept)
    }

    @Test
    fun `an unknown role, a look-alike, a role outside the catalogue, a duplicate and a non-list are refused`() {
        listOf(
            "[\"tenant.admins\"]",
            "[\"Tenant.Admin\"]",
            "[\"platform.support\"]",
            "[\"tenant.admin\",\"tenant.admin\"]",
            "[1]",
            "\"tenant.admin\"",
            "not json",
        ).forEach { json ->
            assertThat(check(json)).describedAs(json).isInstanceOf(ConstraintVerdict.Refuse::class.java)
        }
    }

    @Test
    fun `another variation point gets no verdict so setPolicy refuses it`() {
        assertThat(check("[\"tenant.admin\"]", key = "VP-74")).isNull()
    }
}
