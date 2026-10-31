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
    fun `the gstCatalogue entry of libs_versions_toml equals 1_32_0 by string equality`() {
        val toml = Files.readString(Path.of("..", "gradle", "libs.versions.toml"))
        assertThat(pinOf(toml)).isEqualTo(PINNED)
    }

    @Test
    fun `the pin check refuses 1_14_0, 1_30_0, 1_33_0 and a snapshot of 1_32_0`() {
        listOf("1.14.0", "1.30.0", "1.33.0", "1.32.0-SNAPSHOT", "1.32.0 ", "1.32").forEach { version ->
            assertThat(pinOf("[versions]\ngstCatalogue = \"$version\"\n")).describedAs(version).isNotEqualTo(PINNED)
        }
        assertThat(pinOf("[versions]\ngstCatalogue = \"1.32.0\"\n")).isEqualTo(PINNED)
        assertThat(pinOf("[versions]\nother = \"1.32.0\"\n")).isNull()
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

    private companion object {
        const val PINNED = "1.32.0"
    }
}
