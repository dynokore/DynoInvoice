package com.dynokore.gst.notification.domain.ownership

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

class ExceptionGroupOwnerTest {
    private val owners = CodeOwners(mapOf("SUB-005" to "Platform", "SUB-004" to "Tax operations"))

    private fun group(
        entity: String = "E1",
        code: String = "SUB-005",
        cp: String? = null,
    ) = ExceptionGroup(entity, code, cp)

    private fun rule(
        entity: String? = null,
        code: String? = null,
        cp: String? = null,
        team: String,
    ) = OwnerRule(entity, code, cp, team)

    private fun owner(
        g: ExceptionGroup,
        option: OwnerOption,
        rules: List<OwnerRule> = emptyList(),
    ) = ExceptionGroupOwner.ownerOf(g, option, rules, owners)

    private fun check(
        option: OwnerOption,
        rules: List<OwnerRule> = emptyList(),
        available: Boolean = true,
    ) = ExceptionGroupOwner.check(option, rules, owners, available)

    @Test
    fun `INV-AC-16255 DEFAULT_OWNER gives the default owner of the code`() {
        owner(group(code = "SUB-005"), OwnerOption.DEFAULT_OWNER) shouldBe "Platform"
        owner(group(code = "SUB-004"), OwnerOption.DEFAULT_OWNER) shouldBe "Tax operations"
    }

    @Test
    fun `INV-AC-16255 DEFAULT_OWNER ignores a matching rule`() {
        owner(group(), OwnerOption.DEFAULT_OWNER, listOf(rule(entity = "E1", team = "Centre A"))) shouldBe "Platform"
    }

    @Test
    fun `INV-AC-16255 ROUTING_RULES with no rules gives the defaults`() {
        owner(group(code = "SUB-005"), OwnerOption.ROUTING_RULES) shouldBe "Platform"
        owner(group(code = "SUB-004"), OwnerOption.ROUTING_RULES) shouldBe "Tax operations"
    }

    @Test
    fun `INV-AC-16255 an entity rule routes its entity and the default serves others`() {
        val rules = listOf(rule(entity = "E1", team = "Centre A"))
        owner(group(entity = "E1"), OwnerOption.ROUTING_RULES, rules) shouldBe "Centre A"
        owner(group(entity = "E2"), OwnerOption.ROUTING_RULES, rules) shouldBe "Platform"
    }

    @Test
    fun `INV-AC-16255 the first matching rule wins`() {
        val byEntity = rule(entity = "E1", team = "Centre A")
        val byCode = rule(code = "SUB-005", team = "Centre B")
        owner(group(), OwnerOption.ROUTING_RULES, listOf(byEntity, byCode)) shouldBe "Centre A"
        owner(group(), OwnerOption.ROUTING_RULES, listOf(byCode, byEntity)) shouldBe "Centre B"
    }

    @Test
    fun `INV-AC-16255 a counterparty rule never matches a null counterparty`() {
        val rules = listOf(rule(cp = "C9", team = "Centre C"))
        owner(group(cp = null), OwnerOption.ROUTING_RULES, rules) shouldBe "Platform"
        owner(group(cp = "C9"), OwnerOption.ROUTING_RULES, rules) shouldBe "Centre C"
    }

    @Test
    fun `INV-AC-16255 selector comparison is case sensitive`() {
        val rules = listOf(rule(entity = "e1", team = "Centre A"))
        owner(group(entity = "E1"), OwnerOption.ROUTING_RULES, rules) shouldBe "Platform"
    }

    @Test
    fun `INV-AC-16255 an unknown code throws and never returns blank`() {
        shouldThrow<IllegalArgumentException> { owner(group(code = "ZZZ-999"), OwnerOption.DEFAULT_OWNER) }
        shouldThrow<IllegalArgumentException> { owner(group(code = "ZZZ-999"), OwnerOption.ROUTING_RULES) }
        owner(group(), OwnerOption.ROUTING_RULES, listOf(rule(entity = "E1", team = "Centre A"))).shouldNotBeBlank()
    }

    @Test
    fun `INV-AC-16255 check refuses ROUTING_RULES while the point is planned`() {
        check(OwnerOption.ROUTING_RULES, available = false) shouldBe
            OwnerSettingCheck.Refused(OwnerRefusal.POINT_PLANNED)
        val rules = listOf(rule(entity = "E1", team = "A"))
        check(OwnerOption.ROUTING_RULES, rules, available = false) shouldBe
            OwnerSettingCheck.Refused(OwnerRefusal.POINT_PLANNED)
    }

    @Test
    fun `INV-AC-16255 check refuses rules under DEFAULT_OWNER`() {
        check(OwnerOption.DEFAULT_OWNER, listOf(rule(entity = "E1", team = "A"))) shouldBe
            OwnerSettingCheck.Refused(OwnerRefusal.RULES_WITHOUT_OPTION)
    }

    @Test
    fun `INV-AC-16255 check refuses a duplicate rule whatever the team`() {
        val rules = listOf(rule(entity = "E1", team = "A"), rule(entity = "E1", team = "B"))
        check(OwnerOption.ROUTING_RULES, rules) shouldBe OwnerSettingCheck.Refused(OwnerRefusal.DUPLICATE_RULE)
    }

    @Test
    fun `INV-AC-16255 check refuses a rule with an unknown code`() {
        check(OwnerOption.ROUTING_RULES, listOf(rule(code = "ZZZ-999", team = "A"))) shouldBe
            OwnerSettingCheck.Refused(OwnerRefusal.UNKNOWN_CODE)
    }

    @Test
    fun `INV-AC-16255 check accepts valid settings`() {
        val rules = listOf(rule(entity = "E1", team = "A"), rule(code = "SUB-004", team = "B"))
        check(OwnerOption.ROUTING_RULES, rules) shouldBe OwnerSettingCheck.Valid
        check(OwnerOption.ROUTING_RULES) shouldBe OwnerSettingCheck.Valid
        check(OwnerOption.DEFAULT_OWNER, available = true) shouldBe OwnerSettingCheck.Valid
        check(OwnerOption.DEFAULT_OWNER, available = false) shouldBe OwnerSettingCheck.Valid
    }

    @Test
    fun `INV-AC-16255 constructors refuse invalid values`() {
        shouldThrow<IllegalArgumentException> { OwnerRule(null, null, null, "A") }
        shouldThrow<IllegalArgumentException> { OwnerRule("E1", null, null, " ") }
        shouldThrow<IllegalArgumentException> { OwnerRule(" ", null, null, "A") }
        shouldThrow<IllegalArgumentException> { OwnerRule(null, "", null, "A") }
        shouldThrow<IllegalArgumentException> { OwnerRule(null, null, " ", "A") }
        shouldThrow<IllegalArgumentException> { CodeOwners(mapOf("SUB-005" to " ")) }
        shouldThrow<IllegalArgumentException> { CodeOwners(mapOf("" to "Platform")) }
        shouldThrow<IllegalArgumentException> { ExceptionGroup(" ", "SUB-005", null) }
        shouldThrow<IllegalArgumentException> { ExceptionGroup("E1", " ", null) }
        shouldThrow<IllegalArgumentException> { ExceptionGroup("E1", "SUB-005", " ") }
    }

    @Test
    fun `INV-AC-16255 fromKey accepts exact names only`() {
        OwnerOption.fromKey("DEFAULT_OWNER") shouldBe OwnerOption.DEFAULT_OWNER
        OwnerOption.fromKey("ROUTING_RULES") shouldBe OwnerOption.ROUTING_RULES
        listOf("default_owner", "DEFAULT_OWNER ", "").forEach { k ->
            shouldThrow<IllegalArgumentException> { OwnerOption.fromKey(k) }
        }
        OwnerOption.entries.map { it.name } shouldBe listOf("DEFAULT_OWNER", "ROUTING_RULES")
    }
}
