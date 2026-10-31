package com.dynokore.gst.evidence.retention

import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.application.retention.RetentionPolicy
import com.dynokore.gst.evidence.domain.retention.RetentionTier
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-Q-12590 A: the default years are the number n of the DEFAULT option `YEARS_<n>` of VP-43 in the catalogue. */
class DefaultYearsTest {
    /** The shipped catalogue with the key of the DEFAULT option of VP-43 replaced by [defaultKey]. */
    private fun catalogueWith(
        real: VariationPointCatalogue,
        defaultKey: String,
    ): VariationPointCatalogue =
        VariationPointCatalogue { key ->
            real.find(key)?.let { definition ->
                definition.copy(
                    options =
                        definition.options.map {
                            if (it.strictness == Strictness.DEFAULT) it.copy(key = defaultKey) else it
                        },
                )
            }
        }

    @Test
    fun `the shipped catalogue gives 5, a YEARS_7 default gives 7 for every tier, and FIVE fails`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val real = s.bean(VariationPointCatalogue::class.java)
            RetentionPolicy.defaultYears(real) shouldBe 5
            RetentionPolicy.defaultYears(catalogueWith(real, "YEARS_7")) shouldBe 7
            shouldThrow<IllegalArgumentException> { RetentionPolicy.defaultYears(catalogueWith(real, "FIVE")) }

            val effective =
                EffectiveRetention(
                    s.bean(PolicyReader::class.java),
                    s.bean(TenantContext::class.java),
                    RetentionPolicy.defaultYears(catalogueWith(real, "YEARS_7")),
                    JsonTierYearsReader(),
                )
            val tenant = s.newTenant()
            val scope = s.bean(TenantScope::class.java)
            val runner = s.bean(TransactionRunner::class.java)
            val years =
                RetentionTier.entries.map { tier ->
                    scope.bind(tenant, "svc:evidence-svc") {
                        runner.inTransaction { effective.years("ENT-A", tier, RetentionService.today()) }
                    }
                }
            years shouldBe listOf(7, 7, 7)
        }
}
