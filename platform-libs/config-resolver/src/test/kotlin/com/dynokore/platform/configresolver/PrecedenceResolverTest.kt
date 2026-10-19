package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.TestCatalogue.ALL_LEVELS
import com.dynokore.platform.configresolver.TestCatalogue.BY_FORM
import com.dynokore.platform.configresolver.TestCatalogue.ENTITY_POLICY
import com.dynokore.platform.configresolver.TestCatalogue.MUST_ELECT
import com.dynokore.platform.configresolver.TestCatalogue.PLANNED
import com.dynokore.platform.configresolver.TestCatalogue.RETIRED
import com.dynokore.platform.configresolver.TestCatalogue.UNKNOWN
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDate
import java.util.UUID

/** INV-REQ-1068 / INV-REQ-1242 precedence, INV-REQ-1251 status, INV-REQ-1248 forms: pure logic, no database. */
class PrecedenceResolverTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.of(2026, 10, 1)

    private data class Row(
        val key: VariationPointKey,
        val ref: ScopeRef,
        val source: SettingSource,
        val setting: StoredSetting,
    )

    private val rows = mutableListOf<Row>()

    /** Where a stored row came from; `null` [from] is 30 days before the test day. */
    private data class Origin(
        val source: SettingSource = SettingSource.OVERRIDE,
        val from: LocalDate? = null,
        val profile: Pair<String, String>? = null,
    )

    private fun put(
        key: VariationPointKey,
        level: ScopeLevel,
        id: String,
        option: String,
        origin: Origin = Origin(),
    ): StoredSetting {
        val (source, since, profile) = origin
        val from = since ?: day.minusDays(30)
        val setting =
            StoredSetting(
                UUID.randomUUID(),
                listOf(SelectedOption(option)),
                source,
                profile?.first,
                profile?.second,
                from,
            )
        rows += Row(key, ScopeRef(level, id), source, setting)
        return setting
    }

    private fun profileAtTenant(
        key: VariationPointKey,
        option: String,
    ) = put(
        key,
        ScopeLevel.TENANT,
        tenant.toString(),
        option,
        Origin(
            source = SettingSource.PROFILE,
            profile =
                "small-business" to "3",
        ),
    )

    private val e1 get() = PolicyScope.entity(tenant, "E1")

    /** The single option key resolved for [key] in [scope] at [at]. */
    private fun keyOf(
        key: VariationPointKey,
        scope: PolicyScope = e1,
        at: LocalDate = day,
        form: DeploymentForm = DeploymentForm.SAAS,
    ) = resolver(form)
        .resolve(key, scope, at)
        .selected
        .single()
        .key

    private fun resolver(form: DeploymentForm = DeploymentForm.SAAS): PrecedenceResolver {
        val lookup =
            SettingLookup { key, ref, _, source, at ->
                rows
                    .filter { it.key == key && it.ref == ref && it.source == source }
                    .filter { !it.setting.effectiveFrom.isAfter(at) }
                    .maxByOrNull { it.setting.effectiveFrom }
                    ?.setting
            }
        return PrecedenceResolver(TestCatalogue.catalogue, form, lookup)
    }

    private val fullScope =
        PolicyScope.entity(tenant, "E1", group = "G1", feed = "F1", apConnection = "A1", clientEntity = "C1")

    @Test
    fun `INV-AC-2091 an entity override beats the tenant's profile value and the default`() {
        profileAtTenant(ENTITY_POLICY, "FOUR_EYES_PLUS")
        val entity = put(ENTITY_POLICY, ScopeLevel.ENTITY, "E1", "SOLE_OPERATOR")

        val resolved = resolver().resolve<ApprovalRule>(ENTITY_POLICY, PolicyScope.entity(tenant, "E1"), day)

        assertEquals(ApprovalRule.SOLE_OPERATOR, resolved.value)
        assertEquals(PolicySource.Override(ScopeLevel.ENTITY, "E1", entity.id), resolved.source)
        assertEquals(null, resolved.profileVersion)
    }

    @Test
    fun `INV-AC-2092 with nothing set, the profile value is used with its id and version`() {
        val profile = profileAtTenant(ENTITY_POLICY, "FOUR_EYES_PLUS")

        val resolved = resolver().resolve<ApprovalRule>(ENTITY_POLICY, PolicyScope.entity(tenant, "E1"), day)

        assertEquals(ApprovalRule.FOUR_EYES_PLUS, resolved.value)
        assertEquals(
            PolicySource.Profile("small-business", "3", ScopeLevel.TENANT, tenant.toString(), profile.id),
            resolved.source,
        )
        assertEquals("3", resolved.profileVersion)
    }

    @Test
    fun `any override beats a profile value, even a broader one (INV-REQ-1068 b1)`() {
        put(
            ALL_LEVELS,
            ScopeLevel.ENTITY,
            "E1",
            "FOUR_EYES_PLUS",
            Origin(
                source = SettingSource.PROFILE,
                profile =
                    "p" to "1",
            ),
        )
        put(ALL_LEVELS, ScopeLevel.INSTALLATION, "installation", "SOLE_OPERATOR")

        assertEquals(
            "SOLE_OPERATOR",
            resolver()
                .resolve(ALL_LEVELS, fullScope, day)
                .selected
                .single()
                .key,
        )
    }

    @Test
    fun `the default is the option marked DEFAULT, not the first listed (INV-Q-2936)`() {
        val resolved = resolver().resolve<ApprovalRule>(ENTITY_POLICY, PolicyScope.entity(tenant, "E1"), day)

        assertEquals(ApprovalRule.TWO_PERSON, resolved.value)
        assertEquals(PolicySource.CatalogueDefault, resolved.source)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @EnumSource(ScopeLevel::class)
    fun `all seven levels take part, the most specific set one wins`(winner: ScopeLevel) {
        val ids = fullScope.chain().associate { it.level to it.id }
        ScopeLevel.entries.filter { it <= winner }.forEach { level ->
            put(ALL_LEVELS, level, ids.getValue(level), if (level == winner) "FOUR_EYES_PLUS" else "SOLE_OPERATOR")
        }

        val resolved = resolver().resolve(ALL_LEVELS, fullScope, day)

        assertEquals("FOUR_EYES_PLUS", resolved.selected.single().key)
        assertEquals(winner, (resolved.source as PolicySource.Override).level)
    }

    @Test
    fun `the precedence is client entity, AP connection, feed, entity, group, tenant, installation`() {
        assertEquals(
            listOf(
                ScopeLevel.CLIENT_ENTITY,
                ScopeLevel.AP_CONNECTION,
                ScopeLevel.FEED,
                ScopeLevel.ENTITY,
                ScopeLevel.GROUP,
                ScopeLevel.TENANT,
                ScopeLevel.INSTALLATION,
            ),
            fullScope.chain().map { it.level },
        )
    }

    @Test
    fun `a value at a level the row does not allow takes no part`() {
        put(ENTITY_POLICY, ScopeLevel.GROUP, "G1", "SOLE_OPERATOR")

        assertEquals(PolicySource.CatalogueDefault, resolver().resolve(ENTITY_POLICY, fullScope, day).source)
    }

    @Test
    fun `INV-REQ-1351 the value in force at the transaction date applies, not a later one`() {
        put(ENTITY_POLICY, ScopeLevel.ENTITY, "E1", "SOLE_OPERATOR", Origin(from = LocalDate.of(2026, 1, 1)))
        put(ENTITY_POLICY, ScopeLevel.ENTITY, "E1", "FOUR_EYES_PLUS", Origin(from = LocalDate.of(2026, 7, 1)))
        val scope = PolicyScope.entity(tenant, "E1")

        assertEquals("SOLE_OPERATOR", keyOf(ENTITY_POLICY, scope, LocalDate.of(2026, 3, 15)))
        assertEquals("FOUR_EYES_PLUS", keyOf(ENTITY_POLICY, scope, LocalDate.of(2026, 7, 1)))
        val before = resolver().resolve(ENTITY_POLICY, scope, LocalDate.of(2025, 12, 31))
        assertEquals(PolicySource.CatalogueDefault, before.source)
    }

    @Test
    fun `INV-REQ-1251 a planned row resolves to its default even when a value is stored`() {
        put(PLANNED, ScopeLevel.TENANT, tenant.toString(), "SOLE_OPERATOR")

        val resolved = resolver().resolve(PLANNED, PolicyScope.tenant(tenant), day)

        assertEquals("TWO_PERSON", resolved.selected.single().key)
        assertEquals(PolicySource.CatalogueDefault, resolved.source)
        assertEquals(listOf("TWO_PERSON"), TestCatalogue.rows.getValue(PLANNED).offeredOptions(DeploymentForm.SAAS))
    }

    @Test
    fun `INV-Q-2930 retired and unknown keys are refused, never defaulted`() {
        assertThrows(VariationPointRetired::class.java) { resolver().resolve(RETIRED, PolicyScope.tenant(tenant), day) }
        assertThrows(UnknownVariationPoint::class.java) { resolver().resolve(UNKNOWN, PolicyScope.tenant(tenant), day) }
        assertEquals(emptyList<String>(), TestCatalogue.rows.getValue(RETIRED).offeredOptions(DeploymentForm.SAAS))
    }

    @Test
    fun `INV-Q-2930 a must-elect row with nothing set raises ElectionRequired`() {
        assertThrows(ElectionRequired::class.java) { resolver().resolve(MUST_ELECT, e1, day) }

        put(MUST_ELECT, ScopeLevel.ENTITY, "E1", "CASH")
        assertEquals("CASH", keyOf(MUST_ELECT))
    }

    @Test
    fun `a stored option the catalogue does not define is refused`() {
        put(ENTITY_POLICY, ScopeLevel.ENTITY, "E1", "NOT_AN_OPTION")

        assertThrows(StoredValueNotInCatalogue::class.java) { resolver().resolve(ENTITY_POLICY, e1, day) }
    }

    @Test
    fun `an enum that lacks the resolved key is a mismatch, not a guess (TPL-10)`() {
        put(MUST_ELECT, ScopeLevel.ENTITY, "E1", "CASH")

        assertThrows(OptionKeyMismatch::class.java) { resolver().resolve<ApprovalRule>(MUST_ELECT, e1, day) }
    }

    @Test
    fun `both forms offer the same options, only a by-form default differs (INV-REQ-1248)`() {
        TestCatalogue.rows.values.forEach {
            assertEquals(it.offeredOptions(DeploymentForm.SAAS), it.offeredOptions(DeploymentForm.PRIVATE), "${it.key}")
        }
        assertEquals("ON", keyOf(BY_FORM, PolicyScope.installation(), form = DeploymentForm.SAAS))
        assertEquals("OFF", keyOf(BY_FORM, PolicyScope.installation(), form = DeploymentForm.PRIVATE))
    }

    @Test
    fun `the platform tenant has no tenant-level scope`() {
        assertThrows(IllegalArgumentException::class.java) { PolicyScope.tenant(TenantId.PLATFORM) }
    }

    @Test
    fun `a subject type is a lower-case token`() {
        assertThrows(IllegalArgumentException::class.java) { ResolutionSubject("Submission", "s-1") }
        assertThrows(IllegalArgumentException::class.java) { ResolutionSubject("sub mission", "s-1") }
        assertEquals("submission", ResolutionSubject("submission", "s-1").type)
    }

    @Test
    fun `a row needs exactly one DEFAULT option unless it must be elected or defaults by form`() {
        assertThrows(IllegalArgumentException::class.java) {
            TestCatalogue.rows
                .getValue(ENTITY_POLICY)
                .copy(options = listOf(OptionSpec("A", Strictness.NEUTRAL), OptionSpec("B", Strictness.STRICTER)))
        }
    }
}
