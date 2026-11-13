package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.adapters.policy.PolicySafetyMargins
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.pack.MarginUnit
import com.dynokore.gst.submission.application.pack.PackDefaultMargin
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.pack.PackMarginAnswer
import com.dynokore.platform.configresolver.BoundUnavailable
import com.dynokore.platform.configresolver.ElectionRequired
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VariationPointRetired
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The margin adapter against fakes of the resolver and the stored pack margin (INV-AC-20848). */
class PolicySafetyMarginsTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.of(2026, 11, 28)
    private val otherDay = LocalDate.of(2026, 11, 29)
    private val calls = mutableListOf<Triple<String, PolicyScope, LocalDate>>()

    private fun <T : Any> create(
        type: Class<T>,
        vararg args: Any,
    ): T =
        type.declaredConstructors
            .first { c ->
                c.parameterCount == args.size && c.parameterTypes.none { it.simpleName == "DefaultConstructorMarker" }
            }.also { it.isAccessible = true }
            .newInstance(*args)
            .let(type::cast)

    private fun setting(
        key: String,
        at: LocalDate,
        option: String,
        valueJson: String? = null,
    ): ResolvedSetting =
        create(
            ResolvedSetting::class.java,
            key,
            at,
            listOf(SelectedOption(option, valueJson)),
            PolicySource.CatalogueDefault,
            emptyList<Any>(),
        )

    private val failures: Map<String, () -> RuntimeException> =
        mapOf(
            "E4a" to { create(UnknownVariationPoint::class.java, "x") },
            "E4b" to { create(VariationPointRetired::class.java, "x") },
            "E4c" to { create(ElectionRequired::class.java, "x") },
            "E4d" to { create(BoundUnavailable::class.java, "x", "y") },
        )

    private val reader =
        object : PolicyReader {
            override fun resolve(
                key: VariationPointKey,
                scope: PolicyScope,
                at: LocalDate,
            ): ResolvedSetting {
                calls += Triple(key.value, scope, at)
                failures[scope.entity]?.let { throw it() }
                return when (scope.entity) {
                    "E1" -> setting(key.value, at, "LARGER", """{"value":10,"unit":"DAYS"}""")
                    "E2" -> setting(key.value, at, "LARGER", """{"value":36,"unit":"HOURS"}""")
                    "E3" -> setting(key.value, at, "PACK_DEFAULT")
                    "E5" -> setting(key.value, at, "LARGER", """{"value":3,"unit":"WEEKS"}""")
                    "E6" -> setting(key.value, at, "LARGER", "not json")
                    "E7" -> setting(key.value, at, "LARGER", null)
                    "E8" -> setting(key.value, at, "LARGER", """{"value":3.5,"unit":"DAYS"}""")
                    "E9" -> setting(key.value, at, "SOMETHING_ELSE")
                    else -> error("unexpected entity")
                }
            }
        }

    private val pack =
        PackDefaultMargin { t, d ->
            if (t == tenant && d == day) {
                PackMarginAnswer.Known(checkNotNull(PackMargin.of(7, "DAYS")))
            } else {
                PackMarginAnswer.Unknown
            }
        }

    private val fixedElsewhere = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val adapter = PolicySafetyMargins(reader, pack)

    private fun ask(
        entity: String,
        on: LocalDate = day,
    ) = adapter.effective(tenant, entity, on)

    private fun known(
        value: Long,
        unit: String,
    ) = SafetyMarginAnswer.Known(checkNotNull(PackMargin.of(value, unit)))

    @Test
    fun `INV-AC-20848 LARGER gives its typed duration and the resolver gets VP-18, entity scope and the date`() {
        ask("E1") shouldBe known(10, "DAYS")
        calls.single().first shouldBe "VP-18"
        calls.single().second.entity shouldBe "E1"
        calls.single().third shouldBe day
    }

    @Test
    fun `INV-AC-20848 a duration in hours is returned in hours for the use case to convert`() {
        ask("E2") shouldBe known(36, "HOURS")
        (ask("E2") as SafetyMarginAnswer.Known).margin.unit shouldBe MarginUnit.HOURS
    }

    @Test
    fun `INV-AC-20848 PACK_DEFAULT gives the stored pack margin of the date, unavailable when none is known`() {
        ask("E3") shouldBe known(7, "DAYS")
        ask("E3", otherDay) shouldBe SafetyMarginAnswer.Unavailable
    }

    @Test
    fun `INV-AC-20848 every resolver exception gives unavailable, never a default`() {
        listOf("E4a", "E4b", "E4c", "E4d").forEach { ask(it) shouldBe SafetyMarginAnswer.Unavailable }
    }

    @Test
    fun `INV-AC-20848 an unreadable value, a unit the adapter cannot convert and an unknown option give unavailable`() {
        listOf("E5", "E6", "E7", "E8", "E9").forEach { ask(it) shouldBe SafetyMarginAnswer.Unavailable }
    }

    @Test
    fun `INV-AC-20848 the adapter reads no clock, the date passed in is the date resolved`() {
        // a clock that would give another day exists here but cannot reach the adapter: its constructor takes none
        fixedElsewhere.instant() shouldBe Instant.parse("2031-01-01T00:00:00Z")
        ask("E1", otherDay)
        calls.single().third shouldBe otherDay
        PolicySafetyMargins::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
            .none { it == Clock::class.java } shouldBe true
    }

    @Test
    fun `INV-AC-20848 the key is the catalogue entry of VP-18`() {
        PolicySafetyMargins.KEY shouldBe VariationPoint.VP_18.id
        PolicySafetyMargins.KEY shouldBe "VP-18"
    }

    @Test
    fun `INV-AC-20848 no literal margin number is in the production source of the adapter`() {
        val source = Files.readString(Path.of(sourcePath()))
        val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/") }
        val number = Regex("""(?<![A-Za-z_0-9"-])\d+(?![A-Za-z_0-9"-])""")
        val quoted = Regex("\"[^\"]*\"")
        code.filter { number.containsMatchIn(it.replace(quoted, "")) } shouldBe emptyList()
    }

    private fun sourcePath(): String {
        var dir: Path? = Path.of("").toAbsolutePath()
        val rel =
            "submission-adapters/src/main/kotlin/com/dynokore/gst/submission/adapters/policy/PolicySafetyMargins.kt"
        while (dir != null && !Files.exists(dir.resolve(rel))) dir = dir.parent
        return checkNotNull(dir) { "repository root not found" }.resolve(rel).toString()
    }
}
