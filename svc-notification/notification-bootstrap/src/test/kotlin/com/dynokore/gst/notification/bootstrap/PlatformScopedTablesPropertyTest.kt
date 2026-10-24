package com.dynokore.gst.notification.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * INV-Q-9743 A: `inv.tenancy.platform-scoped-tables` of the real application.yaml is exactly the sys-owned table-valued
 * function the generated code carries and OutboxSchema.platformScopedTables does not list.
 */
class PlatformScopedTablesPropertyTest {
    @Test
    fun `the declared platform-scoped tables equal notification claim_due`() {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream("application.yaml")).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        val root = Yaml().load<Map<String, Any?>>(text)

        @Suppress("UNCHECKED_CAST")
        val tenancy = (root["inv"] as Map<String, Any?>)["tenancy"] as Map<String, Any?>
        val declared = tenancy["platform-scoped-tables"].toString().split(',').map { it.trim() }
        declared shouldBe listOf("notification.claim_due")
    }
}
