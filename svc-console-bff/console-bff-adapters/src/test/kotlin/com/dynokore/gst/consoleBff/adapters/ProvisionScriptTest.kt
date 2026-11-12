package com.dynokore.gst.consoleBff.adapters

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `db/provision.sql` creates the schema owned by the migration role; no script grants CREATE on the database. */
class ProvisionScriptTest {
    private val provision = checkNotNull(javaClass.getResource("/db/provision.sql")).readText()
    private val roles = checkNotNull(javaClass.getResource("/db/roles.sql")).readText()

    @Test
    fun `INV-AC-14457 provision creates the schema console_bff owned by console_bff_migrate`() {
        provision.contains("create schema console_bff authorization ${ConsoleDb.MIGRATE};") shouldBe true
    }

    @Test
    fun `INV-AC-14457 roles never grant create on the database`() {
        Regex("grant\\s+create\\s+on\\s+database", RegexOption.IGNORE_CASE).containsMatchIn(roles) shouldBe false
        Regex("grant\\s+create\\s+on\\s+database", RegexOption.IGNORE_CASE).containsMatchIn(provision) shouldBe false
    }

    @Test
    fun `INV-AC-21022 the binding section equals the generator output`() {
        val section =
            provision
                .substringAfter("-- BEGIN BindingKeyMigration.ddl\n")
                .substringBefore("\n-- END BindingKeyMigration.ddl")
        section shouldBe BindingKeyMigration.ddl(ConsoleDb.SCHEMA, ConsoleDb.SYS, ConsoleDb.RUNTIME)
    }

    @Test
    fun `INV-AC-21022 the key inserted is the UTF-8 bytes of the secret`() {
        val hex =
            ConsoleDb.BINDING_SECRET.toByteArray(Charsets.UTF_8).joinToString("") {
                it.toUByte().toString(HEX_RADIX).padStart(2, '0')
            }
        provision.contains("decode('$hex', 'hex')") shouldBe true
    }

    private companion object {
        const val HEX_RADIX = 16
    }
}
