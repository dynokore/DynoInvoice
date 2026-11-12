package com.dynokore.gst.canonical.application.entity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class EntityConstitutionRuleTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val t2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val f1 = EntityConstitutionFact(t1, "ENT-1", "PRIVATE_LIMITED", 5)
    private val f2 = EntityConstitutionFact(t1, "ENT-1", "PARTNERSHIP", 4)
    private val f3 = EntityConstitutionFact(t1, "ENT-1", "SOLE_PROPRIETOR", 5)
    private val f4 = EntityConstitutionFact(t1, "ENT-1", "PARTNERSHIP", 6)
    private val f6 = EntityConstitutionFact(t2, "ENT-1", "PUBLIC_LIMITED", 1)

    private val table = mutableMapOf<Pair<UUID, String>, EntityConstitutionRow>()

    private fun apply(f: EntityConstitutionFact): EntityConstitutionRow? {
        val key = f.tenantId to f.entityRef
        val next = EntityConstitutionRule.next(table[key], f)
        if (next != null) table[key] = next
        return next
    }

    @Test
    fun `INV-AC-21398 sequence F1 F2 F3 F4 F6 keeps the newest version per tenant and entity`() {
        apply(f1) shouldBe EntityConstitutionRow("PRIVATE_LIMITED", 5)
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PRIVATE_LIMITED", 5)

        apply(f2) shouldBe null
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PRIVATE_LIMITED", 5)

        apply(f3) shouldBe null
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PRIVATE_LIMITED", 5)

        apply(f4) shouldBe EntityConstitutionRow("PARTNERSHIP", 6)
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PARTNERSHIP", 6)

        apply(f6) shouldBe EntityConstitutionRow("PUBLIC_LIMITED", 1)
        table[t2 to "ENT-1"] shouldBe EntityConstitutionRow("PUBLIC_LIMITED", 1)
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PARTNERSHIP", 6)
    }

    @Test
    fun `INV-AC-21398 order F4 then F1 ends with PARTNERSHIP version 6`() {
        apply(f4)
        apply(f1) shouldBe null
        table.size shouldBe 1
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PARTNERSHIP", 6)
    }

    @Test
    fun `INV-AC-21398 order F1 then F4 ends with the same row`() {
        apply(f1)
        apply(f4)
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PARTNERSHIP", 6)
    }

    @Test
    fun `INV-AC-21398 F1 twice ends with one entry and the second write is null`() {
        apply(f1) shouldBe EntityConstitutionRow("PRIVATE_LIMITED", 5)
        apply(f1) shouldBe null
        table.size shouldBe 1
        table[t1 to "ENT-1"] shouldBe EntityConstitutionRow("PRIVATE_LIMITED", 5)
    }

    @Test
    fun `INV-AC-21398 version is compared as a number not as text`() {
        val nine = EntityConstitutionRow("A", 9)
        EntityConstitutionRule.next(nine, EntityConstitutionFact(t1, "ENT-1", "B", 10)) shouldBe EntityConstitutionRow("B", 10)
    }

    @Test
    fun `INV-AC-21398 fact with blank entityRef is refused`() {
        assertThrows<IllegalArgumentException> { EntityConstitutionFact(t1, " ", "PARTNERSHIP", 1) }
        assertThrows<IllegalArgumentException> { EntityConstitutionFact(t1, "", "PARTNERSHIP", 1) }
    }

    @Test
    fun `INV-AC-21398 fact with blank constitution is refused`() {
        assertThrows<IllegalArgumentException> { EntityConstitutionFact(t1, "ENT-1", " ", 1) }
        assertThrows<IllegalArgumentException> { EntityConstitutionFact(t1, "ENT-1", "", 1) }
    }

    @Test
    fun `INV-AC-21398 fact with version 0 is refused`() {
        assertThrows<IllegalArgumentException> { EntityConstitutionFact(t1, "ENT-1", "PARTNERSHIP", 0) }
        assertThrows<IllegalArgumentException> { EntityConstitutionFact(t1, "ENT-1", "PARTNERSHIP", -1) }
    }
}
