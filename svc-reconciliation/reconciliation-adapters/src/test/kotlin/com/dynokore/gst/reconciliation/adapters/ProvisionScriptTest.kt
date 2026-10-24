package com.dynokore.gst.reconciliation.adapters

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `db/provision.sql` carries tenancy-kernel's binding DDL unchanged, and the key of
 * [ReconciliationDb.BINDING_SECRET].
 */
class ProvisionScriptTest {
    private val script = checkNotNull(javaClass.getResource("/db/provision.sql")).readText()

    @Test
    fun `the binding section equals the generator output`() {
        val section =
            script
                .substringAfter(
                    "-- BEGIN BindingKeyMigration.ddl\n",
                ).substringBefore("\n-- END BindingKeyMigration.ddl")
        section shouldBe
            BindingKeyMigration.ddl(ReconciliationDb.SCHEMA, ReconciliationDb.SYS, ReconciliationDb.RUNTIME)
    }

    @Test
    fun `the key inserted is the UTF-8 bytes of the secret`() {
        val hex =
            ReconciliationDb.BINDING_SECRET.toByteArray(Charsets.UTF_8).joinToString("") {
                it.toUByte().toString(HEX_RADIX).padStart(2, '0')
            }
        script.contains("decode('$hex', 'hex')") shouldBe true
    }

    private companion object {
        const val HEX_RADIX = 16
    }
}
