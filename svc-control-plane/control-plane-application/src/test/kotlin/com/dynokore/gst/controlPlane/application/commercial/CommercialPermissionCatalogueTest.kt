package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The constants of [CommercialPermission] against the generated operation-to-roles map of the pinned gst-catalogue. */
class CommercialPermissionCatalogueTest {
    private val constants =
        CommercialPermission::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.onEach { it.isAccessible = true }
            .map { it.get(null) as String }

    @Test
    fun `INV-AC-17761 the constants are exactly the two view keys`() {
        constants.toSet() shouldBe setOf("licence.view", "tenant-provisioning-request.view")
        constants.size shouldBe 2
    }

    @Test
    fun `INV-AC-17761 each constant is a key of the catalogue map for SaaS held by commercial admin only`() {
        val keys = OperationRoles.keys(DeploymentForm.SAAS)
        constants.forEach { key ->
            (key in keys) shouldBe true
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).entries.map { it.key } shouldBe
                listOf("commercial.admin")
        }
    }
}
