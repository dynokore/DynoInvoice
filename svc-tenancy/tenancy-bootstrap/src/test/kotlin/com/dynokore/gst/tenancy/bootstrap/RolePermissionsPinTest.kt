package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.PermissionKind
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The gst-catalogue pin and the role cells of the two permissions the elections ask for (INV-Q-10042, INV-Q-10025,
 * INV-Q-7985 B): `entity-config.manage` is held by `entity.admin` only; `entity-config.confirm` by `entity.admin`,
 * `tenant.admin` and `tax.approver`. Sets are compared by equality, never by `contains`.
 */
class RolePermissionsPinTest {
    private val manage = "entity-config.manage"
    private val confirm = "entity-config.confirm"

    private fun holders(
        form: DeploymentForm,
        permission: String,
        cells: Map<String, Map<String, RoleAllowance>> = RolePermissions.cells(form),
    ): Set<String> = cells.filterValues { it.containsKey(permission) }.keys

    @Test
    fun `the gstCatalogue entry of libs_versions_toml is a release at or above 2_31_0`() {
        val toml = Files.readString(Path.of("..", "gradle", "libs.versions.toml"))
        assertThat(atLeastMinimum(pinOf(toml))).isTrue()
    }

    @Test
    fun `the pin check refuses 1_14_0, 2_21_0, 2_30_0, a snapshot of 2_31_0 and look-alikes`() {
        val refused =
            listOf("1.14.0", "2.21.0", "2.30.9", "2.31.0-SNAPSHOT", "2.31.0 ", "2.31", "2.31.0.1", "", "x.31.0")
        refused.forEach {
            assertThat(atLeastMinimum(it)).describedAs(it).isFalse()
        }
        listOf(
            "2.31.0",
            "2.31.1",
            "2.32.0",
            "3.0.0",
        ).forEach { assertThat(atLeastMinimum(it)).describedAs(it).isTrue() }
        assertThat(pinOf("[versions]\ngstCatalogue = \"2.31.0\"\n")).isEqualTo(MINIMUM)
        assertThat(pinOf("[versions]\nother = \"2.31.0\"\n")).isNull()
    }

    @Test
    fun `the roles holding entity-config_manage equal entity_admin and those holding confirm equal the three roles`() {
        DeploymentForm.entries.forEach { form ->
            assertThat(DeploymentForm.entries.map { it.name }).contains("SAAS", "PRIVATE")
            assertThat(holders(form, manage)).describedAs("manage in $form").isEqualTo(setOf("entity.admin"))
            assertThat(holders(form, confirm))
                .describedAs("confirm in $form")
                .isEqualTo(setOf("entity.admin", "tenant.admin", "tax.approver"))
        }
    }

    @Test
    fun `every such cell is PERMITTED and both keys are of kind WRITE`() {
        DeploymentForm.entries.forEach { form ->
            val cells = RolePermissions.cells(form)
            listOf(manage, confirm).forEach { key ->
                holders(form, key).forEach { role ->
                    assertThat(
                        cells.getValue(role).getValue(key),
                    ).describedAs("$role $key $form").isEqualTo(RoleAllowance.PERMITTED)
                }
                assertThat(RolePermissions.kind(key)).describedAs(key).isEqualTo(PermissionKind.WRITE)
            }
        }
    }

    @Test
    fun `look-alike keys have no cell and no kind, and security_officer holds neither`() {
        DeploymentForm.entries.forEach { form ->
            listOf("entity-config.confirm ", "entity-config.admin", "entity.admin").forEach { key ->
                assertThat(holders(form, key)).describedAs("'$key' in $form").isEmpty()
                assertThat(RolePermissions.kind(key)).describedAs("kind of '$key'").isNull()
            }
            assertThat(holders(form, manage)).doesNotContain("security.officer")
            assertThat(holders(form, confirm)).doesNotContain("security.officer")
        }
    }

    @Test
    fun `an expected set without tax_approver does not equal the confirm holders`() {
        val without = setOf("entity.admin", "tenant.admin")
        DeploymentForm.entries.forEach { form ->
            assertThat(holders(form, confirm)).isNotEqualTo(without)
        }
    }

    private fun pinOf(toml: String): String? =
        Regex("""(?m)^gstCatalogue\s*=\s*"([^"]*)"\s*$""").find(toml)?.groupValues?.get(1)

    /** INV-T-16683 publishes the key period-extension.record in 2.31.0; a later release is accepted. */
    private fun atLeastMinimum(version: String?): Boolean {
        val found = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version.orEmpty()) ?: return false
        val parts = found.groupValues.drop(1).map { it.toInt() }
        val floor = MINIMUM.split('.').map { it.toInt() }
        return compareValuesBy(parts, floor, { it[0] }, { it[1] }, { it[2] }) >= 0
    }

    private companion object {
        const val MINIMUM = "2.31.0"
    }
}
