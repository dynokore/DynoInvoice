package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The constants of [ReleasePermission] against the generated operation-to-roles map of the pinned gst-catalogue. */
class ReleasePermissionCatalogueTest {
    private val constants =
        ReleasePermission::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.onEach { it.isAccessible = true }
            .map { it.get(null) as String }

    @Test
    fun `the constants are exactly the two release keys`() {
        constants.toSet() shouldBe setOf("release-train.publish", "release-train.certify")
        constants.size shouldBe 2
    }

    @Test
    fun `each constant is a key of the catalogue map for SaaS with at least one role`() {
        val keys = OperationRoles.keys(DeploymentForm.SAAS)
        constants.forEach { key ->
            (key in keys) shouldBe true
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).entries.shouldNotBeEmpty()
        }
    }
}
