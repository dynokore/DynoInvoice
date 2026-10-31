package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EnvironmentSeparationRulesTest {
    @Test
    fun `only an exact environment name is a tag`() {
        assertEquals(Environment.SANDBOX, Environment.ofTag("SANDBOX"))
        assertEquals(Environment.PRODUCTION, Environment.ofTag("PRODUCTION"))
        listOf(
            null,
            "",
            " ",
            "sandbox",
            "Production",
            "SANDBOX ",
            "PRODUCTION,SANDBOX",
            "*",
            "SANDBOX2",
            "prod-ap",
        ).forEach {
            assertNull(Environment.ofTag(it), "'$it'")
        }
    }

    @Test
    fun `INV-AC-13970 a credential is accepted only when its tag equals the target and its name is no input`() {
        val tags = listOf("PRODUCTION", "SANDBOX", null, "production", "")
        Environment.entries.forEach { target ->
            tags.forEach { tag ->
                val expected =
                    when {
                        tag == target.name -> null
                        Environment.ofTag(tag) != null -> CredentialRefusal.TAG_DIFFERS
                        else -> CredentialRefusal.UNTAGGED
                    }
                // the rule takes no name: whatever the credential is called (prod, sandbox, ...), the outcome is this
                assertEquals(expected, CredentialEnvironmentRule.refusal(target, tag), "'$tag' into $target")
            }
        }
        assertEquals(null, CredentialEnvironmentRule.refusal(Environment.SANDBOX, "SANDBOX"))
        assertEquals(
            CredentialRefusal.TAG_DIFFERS,
            CredentialEnvironmentRule.refusal(Environment.SANDBOX, "PRODUCTION"),
        )
        assertEquals(
            CredentialRefusal.TAG_DIFFERS,
            CredentialEnvironmentRule.refusal(Environment.PRODUCTION, "SANDBOX"),
        )
        assertEquals(CredentialRefusal.UNTAGGED, CredentialEnvironmentRule.refusal(Environment.PRODUCTION, "prod-ap"))
    }

    @Test
    fun `anonymisation replaces every counterparty identity and keeps the amounts, the order and one stand-in each`() {
        val rows =
            listOf(
                SampleRow("Acme Pte Ltd", "UEN-1", BigDecimal("10.10")),
                SampleRow("Beta Trading", "UEN-2", BigDecimal("0.00")),
                SampleRow("Acme Pte Ltd", "UEN-1", BigDecimal("-3")),
                SampleRow("Acme Pte Ltd", "UEN-9", BigDecimal("7")),
            )
        val out = SampleAnonymiser.anonymise(rows)
        assertEquals(rows.map { it.amount }, out.map { it.amount })
        assertTrue(
            out.none {
                it.counterpartyName in setOf("Acme Pte Ltd", "Beta Trading") ||
                    it.counterpartyId.startsWith("UEN")
            },
        )
        assertEquals(out[0].counterpartyId, out[2].counterpartyId)
        assertEquals(3, out.map { it.counterpartyId }.distinct().size)
        assertEquals(emptyList<SampleRow>(), SampleAnonymiser.anonymise(emptyList()))
    }
}
