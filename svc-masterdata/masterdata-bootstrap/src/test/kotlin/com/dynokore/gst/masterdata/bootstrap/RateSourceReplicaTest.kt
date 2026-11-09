package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.rate.ApprovedSource
import com.dynokore.gst.masterdata.adapters.rate.ScopePackStore
import com.dynokore.gst.masterdata.application.rate.ApprovedRateSources
import com.dynokore.gst.masterdata.application.rate.ElectedRateSource
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * INV-T-18775 replicas at the service boundary: the real context over real PostgreSQL with row-level security. The
 * approved sources are read through [ApprovedRateSources]; an election never made gives no source.
 */
class RateSourceReplicaTest {
    private val jan = LocalDate.parse("2026-01-01")
    private val jun = LocalDate.parse("2026-06-01")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun on(block: (RegistryEnv, ScopePackStore, ApprovedRateSources) -> Unit) =
        runRegistry { env ->
            block(
                env,
                env.context.getBean(ScopePackStore::class.java),
                env.context.getBean(ApprovedRateSources::class.java),
            )
        }

    private fun RegistryEnv.tx(block: () -> Unit) =
        ce.inTenant { context.getBean(TransactionRunner::class.java).inTransaction { block() } }

    @Test
    fun `the same RulePackStaged replayed leaves one row`() =
        on { env, packs, _ ->
            val sources = listOf(ApprovedSource("MAS", setOf("MID")))
            env.tx {
                assertThat(packs.stage("sg-scope", "1.0.0", sources, now)).isTrue()
                assertThat(packs.stage("sg-scope", "1.0.0", sources, now)).isFalse()
            }
            assertThat(
                env.count("select count(*) from masterdata.scope_pack_version where tenant_id = '{tenant}'::uuid"),
            ).isEqualTo(1)
        }

    @Test
    fun `an elected source removed by a later activation is refused for dates after the activation`() =
        on { env, packs, approved ->
            env.tx {
                packs.stage("sg-scope", "1.0.0", listOf(ApprovedSource("MAS", setOf("MID"))), now)
                packs.stage("sg-scope", "2.0.0", listOf(ApprovedSource("ECB", setOf("MID"))), now)
                packs.activate("sg-scope", "1.0.0", jan, now)
                packs.activate("sg-scope", "2.0.0", jun, now)
            }
            env.ce.inTenant {
                assertThat(approved.isApproved("MAS", "MID", jan.plusDays(30))).isTrue()
                assertThat(approved.isApproved("MAS", "MID", jun)).isFalse()
                assertThat(approved.isApproved("MAS", "MID", jun.plusDays(1))).isFalse()
                assertThat(approved.isApproved("ECB", "MID", jun)).isTrue()
                assertThat(approved.isApproved("ECB", "MID", jun.minusDays(1))).isFalse()
            }
        }

    @Test
    fun `no active pack, another source, another basis or a look-alike source is not approved`() =
        on { env, packs, approved ->
            env.ce.inTenant { assertThat(approved.isApproved("MAS", "MID", jan)).isFalse() }
            env.tx {
                packs.stage("sg-scope", "1.0.0", listOf(ApprovedSource("MAS", setOf("MID"))), now)
                packs.activate("sg-scope", "1.0.0", jan, now)
            }
            env.ce.inTenant {
                assertThat(approved.isApproved("MAS", "MID", jan)).isTrue()
                assertThat(approved.isApproved("MAS2", "MID", jan)).isFalse()
                assertThat(approved.isApproved("mas", "MID", jan)).isFalse()
                assertThat(approved.isApproved("MAS", "BUY", jan)).isFalse()
                assertThat(approved.isApproved("MAS", "MID", jan.minusDays(1))).isFalse()
            }
        }

    @Test
    fun `the replica of one tenant is not seen by another`() =
        on { env, packs, approved ->
            env.tx {
                packs.stage("sg-scope", "1.0.0", listOf(ApprovedSource("MAS", setOf("MID"))), now)
                packs.activate("sg-scope", "1.0.0", jan, now)
            }
            env.ce.anotherTenant().inTenant { assertThat(approved.isApproved("MAS", "MID", jan)).isFalse() }
        }

    @Test
    fun `an entity that made no election has no elected source`() =
        on { env, _, _ ->
            val elected = env.context.getBean(ElectedRateSource::class.java)
            env.ce.inTenant { assertThat(elected.elected("ENT-NOELECTION", jan)).isNull() }
        }
}
