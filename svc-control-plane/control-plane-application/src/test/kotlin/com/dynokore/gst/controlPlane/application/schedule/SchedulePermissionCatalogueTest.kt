package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The constants of [SchedulePermission] against the generated operation-to-roles map of the pinned gst-catalogue. */
class SchedulePermissionCatalogueTest {
    private val constants =
        SchedulePermission::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.onEach { it.isAccessible = true }
            .map { it.get(null) as String }

    @Test
    fun `INV-AC-19862 the constants are exactly the five schedule keys`() {
        constants.toSet() shouldBe
            setOf(
                "tenant-schedule.set",
                "tenant-schedule.change",
                "tenant-schedule.cancel",
                "tenant-schedule.read",
                "tenant-schedule.approve",
            )
        constants.size shouldBe 5
    }

    @Test
    fun `INV-AC-19862 each constant is a key of the catalogue map for SaaS held by commercial admin only`() {
        val keys = OperationRoles.keys(DeploymentForm.SAAS)
        constants.forEach { key ->
            (key in keys) shouldBe true
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).entries.map { it.key } shouldBe listOf("commercial.admin")
        }
        // every schedule key of the catalogue is one of the constants: a new key must be classified here
        keys.filter { it.startsWith("tenant-schedule.") }.toSet() shouldBe constants.toSet()
    }
}
