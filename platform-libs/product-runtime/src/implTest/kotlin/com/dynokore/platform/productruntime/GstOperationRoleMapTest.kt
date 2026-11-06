package com.dynokore.platform.productruntime

import com.dynokore.platform.productruntime.catalogue.GstOperationRoleMap
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import com.dynokore.gst.catalogue.DeploymentForm as GstForm

/** INV-T-9233, INV-Q-8972 A: the map adapter answers from the generated `OperationRoles` of the form. */
class GstOperationRoleMapTest {
    private val saas = GstOperationRoleMap(GstForm.SAAS)
    private val private = GstOperationRoleMap(GstForm.PRIVATE)

    @Test
    fun `doc view content lists agent operator within scope and tax operator permitted under both forms`() {
        val expected = mapOf("agent.operator" to CellAllowance.WITHIN_SCOPE, "tax.operator" to CellAllowance.PERMITTED)
        for (map in listOf(saas, private)) {
            val roles = map.rolesFor("doc.view.content")
            roles["agent.operator"] shouldBe expected["agent.operator"]
            roles["tax.operator"] shouldBe expected["tax.operator"]
        }
    }

    @Test
    fun `regulatory pack activate is platform engineer under SAAS and empty under PRIVATE`() {
        saas.rolesFor("regulatory-pack.activate") shouldBe mapOf("platform.engineer" to CellAllowance.PERMITTED)
        private.rolesFor("regulatory-pack.activate") shouldBe emptyMap()
    }

    @Test
    fun `retention delete request holds deployment operator under PRIVATE and not under SAAS`() {
        private.rolesFor("retention.delete.request").containsKey("deployment.operator") shouldBe true
        saas.rolesFor("retention.delete.request").containsKey("deployment.operator") shouldBe false
    }

    @Test
    fun `a trailing space, a role name and an unknown key give the empty map`() {
        for (map in listOf(saas, private)) {
            map.rolesFor("doc.view.content ") shouldBe emptyMap()
            map.rolesFor("tenant.admin") shouldBe emptyMap()
            map.rolesFor("Doc.view.content") shouldBe emptyMap()
            map.rolesFor("") shouldBe emptyMap()
            map.rolesFor("no.such.permission") shouldBe emptyMap()
        }
    }
}
