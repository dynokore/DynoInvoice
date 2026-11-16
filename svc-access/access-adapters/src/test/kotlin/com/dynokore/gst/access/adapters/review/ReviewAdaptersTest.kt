package com.dynokore.gst.access.adapters.review

import com.dynokore.gst.access.application.review.CadenceAnswer
import com.dynokore.gst.access.application.review.VpAvailability
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-T-15798: the cadence source reads VP-05 as `setPolicy` stores it and fails closed (INV-REQ-1266). */
class ReviewAdaptersTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val clock = Clock.fixed(Instant.parse("2026-10-21T17:30:00Z"), ZoneOffset.UTC)

    private fun reader(
        selected: List<SelectedOption>?,
        failure: RuntimeException? = null,
        asked: MutableList<Pair<VariationPointKey, PolicyScope>> = mutableListOf(),
    ): PolicyReader =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PolicyReader::class.java)) { _, method, args ->
            check(method.name.startsWith("resolve")) { "the cadence source only resolves" }
            asked += (args[0] as String).let { VariationPointKey(it) } to (args[1] as PolicyScope)
            failure?.let { throw it }
            ResolvedSetting(
                VariationPointKey(args[0] as String),
                args[2] as LocalDate,
                selected ?: listOf(SelectedOption("QUARTERLY")),
                PolicySource.CatalogueDefault,
            )
        } as PolicyReader

    private val tenants: TenantContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            check(method.name.startsWith("current")) { "the cadence source only asks for the tenant" }
            tenant.value
        } as TenantContext

    private fun answer(reader: PolicyReader) = VariationPointReviewCadence(reader, tenants, clock).current()

    @Test
    fun `QUARTERLY and MONTHLY map to their answers and the read is VP-05 at tenant scope`() {
        val asked = mutableListOf<Pair<VariationPointKey, PolicyScope>>()
        assertThat(answer(reader(listOf(SelectedOption("MONTHLY")), asked = asked))).isEqualTo(CadenceAnswer.MONTHLY)
        assertThat(
            answer(reader(listOf(SelectedOption("QUARTERLY")), asked = asked)),
        ).isEqualTo(CadenceAnswer.QUARTERLY)
        assertThat(asked.map { it.first }).containsOnly(VariationPointKey("VP-05"))
        assertThat(asked.map { it.second.toString() }).containsOnly(PolicyScope.tenant(tenant).toString())
    }

    @Test
    fun `none stored reads as the catalogue default QUARTERLY`() {
        assertThat(answer(reader(null))).isEqualTo(CadenceAnswer.QUARTERLY)
    }

    @Test
    fun `a failed read, an unknown option, a look-alike key and two options are unreadable`() {
        assertThat(answer(reader(null, failure = IllegalStateException("down")))).isEqualTo(CadenceAnswer.UNREADABLE)
        listOf("WEEKLY", "monthly", "Monthly", "MONTHLY ", " QUARTERLY", "MONTHLY2", "").forEach {
            assertThat(
                answer(reader(listOf(SelectedOption(it)))),
            ).describedAs("'%s'", it).isEqualTo(CadenceAnswer.UNREADABLE)
        }
        val both = listOf(SelectedOption("QUARTERLY"), SelectedOption("MONTHLY"))
        assertThat(answer(reader(both))).isEqualTo(CadenceAnswer.UNREADABLE)
    }

    private fun catalogue(status: VpStatus?): VariationPointCatalogue {
        val real =
            com.dynokore.platform.productruntime.catalogue
                .GstVariationPointCatalogue("access-svc")
        return object : VariationPointCatalogue {
            override fun find(key: VariationPointKey) =
                if (status ==
                    null
                ) {
                    null
                } else {
                    real.find(key)?.let { if (key.value == "VP-05") it.copy(status = status) else it }
                }

            override fun keys() = real.keys()
        }
    }

    @Test
    fun `only an available VP-05 answers AVAILABLE and a planned, retired or unknown row answers PLANNED`() {
        assertThat(
            CatalogueVp05Availability(catalogue(VpStatus.AVAILABLE)).current(),
        ).isEqualTo(VpAvailability.AVAILABLE)
        assertThat(CatalogueVp05Availability(catalogue(VpStatus.PLANNED)).current()).isEqualTo(VpAvailability.PLANNED)
        assertThat(CatalogueVp05Availability(catalogue(VpStatus.RETIRED)).current()).isEqualTo(VpAvailability.PLANNED)
        assertThat(CatalogueVp05Availability(catalogue(null)).current()).isEqualTo(VpAvailability.PLANNED)
    }
}
