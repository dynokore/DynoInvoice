package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentResponse
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentReturn
import com.dynokore.gst.tenancy.domain.PhaseWindow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private typealias Phase = PhaseAssessmentResponse.Phase

/**
 * assessPhase through the real application context over PostgreSQL (INV-REQ-334, INV-Q-8727 A): the window table, the
 * extrapolation, the thresholds, the refusals and the stored inputs. Test names carry the criterion keys.
 */
class PhaseAssessmentTest {
    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun quarter(
        end: String,
        box4: String,
        days: Long = 90,
    ): PhaseAssessmentReturn {
        val last = day(end)
        return PhaseAssessmentReturn(last.minusDays(days - 1), last, BigDecimal(box4))
    }

    private fun refused(block: () -> Unit): ApiRefusal =
        assertThatThrownBy(block).isInstanceOf(ApiRefusal::class.java).actual() as ApiRefusal

    @Test
    fun `INV-AC-351 INV-AC-8587 periods ending Jan Apr Jul and Oct sum Nov 2024 to Oct 2025 and record the inputs`() {
        run { env ->
            env.withEntity { _, entity ->
                val inWindow =
                    listOf(
                        PhaseAssessmentReturn(day("2024-11-01"), day("2025-01-31"), BigDecimal("10000.00")),
                        PhaseAssessmentReturn(day("2025-02-01"), day("2025-04-30"), BigDecimal("20000.00")),
                        PhaseAssessmentReturn(day("2025-05-01"), day("2025-07-31"), BigDecimal("30000.00")),
                        PhaseAssessmentReturn(day("2025-08-01"), day("2025-10-31"), BigDecimal("40000.00")),
                    )
                // a return that ended in 2024 is outside calendar year 2025 and is not an input
                val outside = PhaseAssessmentReturn(day("2024-08-01"), day("2024-10-31"), BigDecimal("999999.00"))
                val body = env.assess(entity, inWindow + outside).body!!
                assertThat(body.totalAnnualSupplies).isEqualByComparingTo("100000.00")
                assertThat(body.phase).isEqualTo(Phase.PHASE_3)
                assertThat(body.implementationDate).isEqualTo(day("2028-04-01"))
                val inputs = body.inputs!!
                assertThat(inputs.windowStart).isEqualTo(day("2024-11-01"))
                assertThat(inputs.windowEnd).isEqualTo(day("2025-10-31"))
                assertThat(inputs.returns).hasSize(4)
                assertThat(inputs.returns!!.map { it.box4Figures })
                    .containsExactlyElementsOf(inWindow.map { it.box4Figures })
                assertThat(inputs.summedBox4Figures).isEqualByComparingTo("100000.00")
                assertThat(inputs.extrapolated).isFalse()
                assertThat(inputs.daysCovered).isEqualTo(92 + 89 + 92 + 92)
            }
        }
    }

    @Test
    fun `the other two window rows select their period-end months`() {
        run { env ->
            env.withEntity { _, entity ->
                val decReturns = listOf(quarter("2025-03-31", "1000"), quarter("2025-12-31", "2000"))
                val dec = env.assess(entity, decReturns).body!!
                assertThat(dec.inputs!!.windowStart).isEqualTo(day("2025-01-01"))
                assertThat(dec.inputs!!.windowEnd).isEqualTo(day("2025-12-31"))
                val novReturns = listOf(quarter("2025-02-28", "1000"), quarter("2025-11-30", "2000"))
                val nov = env.assess(entity, novReturns).body!!
                assertThat(nov.inputs!!.windowStart).isEqualTo(day("2024-12-01"))
                assertThat(nov.inputs!!.windowEnd).isEqualTo(day("2025-11-30"))
                assertThat(PhaseWindow.entries).hasSize(3)
            }
        }
    }

    @Test
    fun `INV-AC-352 INV-AC-8587 returns covering 200 days are extrapolated by 365 over 200 and the inputs recorded`() {
        run { env ->
            env.withEntity { _, entity ->
                val twoHundredDays = quarter("2025-09-30", "100000.00", days = 200)
                val body = env.assess(entity, listOf(twoHundredDays), extrapolate = true).body!!
                assertThat(body.inputs!!.daysCovered).isEqualTo(200)
                assertThat(body.inputs!!.extrapolated).isTrue()
                assertThat(body.inputs!!.summedBox4Figures).isEqualByComparingTo("100000.00")
                assertThat(body.totalAnnualSupplies).isEqualByComparingTo("182500.00")
                assertThat(body.phase).isEqualTo(Phase.PHASE_3)
                val plain = env.assess(entity, listOf(twoHundredDays), extrapolate = false).body!!
                assertThat(plain.totalAnnualSupplies).isEqualByComparingTo("100000.00")
                assertThat(plain.inputs!!.extrapolated).isFalse()
            }
        }
    }

    @Test
    fun `an extrapolated total is rounded to 2 decimals half up`() {
        run { env ->
            env.withEntity { _, entity ->
                // 0.01 x 365 / 146 = 0.025 exactly: half up gives 0.03 where half even would give 0.02
                val tie = quarter("2025-03-31", "0.01", days = 146)
                val body = env.assess(entity, listOf(tie), extrapolate = true).body!!
                assertThat(body.totalAnnualSupplies).isEqualByComparingTo("0.03")
            }
        }
    }

    @Test
    fun `INV-AC-397 INV-AC-8587 a dormant company with four nil returns is assessed at 0 and 1 Apr 2028`() {
        run { env ->
            env.withEntity { _, entity ->
                val nil =
                    listOf(
                        PhaseAssessmentReturn(day("2025-01-01"), day("2025-03-31"), BigDecimal.ZERO),
                        PhaseAssessmentReturn(day("2025-04-01"), day("2025-06-30"), BigDecimal.ZERO),
                        PhaseAssessmentReturn(day("2025-07-01"), day("2025-09-30"), BigDecimal.ZERO),
                        PhaseAssessmentReturn(day("2025-10-01"), day("2025-12-31"), BigDecimal.ZERO),
                    )
                val body = env.assess(entity, nil).body!!
                assertThat(body.totalAnnualSupplies).isEqualByComparingTo("0")
                assertThat(body.implementationDate).isEqualTo(day("2028-04-01"))
                assertThat(body.phase).isEqualTo(Phase.PHASE_3)
                assertThat(body.inputs!!.returns).hasSize(4)
            }
        }
    }

    @Test
    fun `INV-AC-1625 INV-AC-8587 a total exactly at a threshold falls into the earlier wave`() {
        run { env ->
            env.withEntity { _, entity ->
                val expected =
                    listOf(
                        "200000.00" to Phase.PHASE_3,
                        "200000.01" to Phase.PHASE_4,
                        "1000000.00" to Phase.PHASE_4,
                        "1000000.01" to Phase.PHASE_5,
                        "4000000.00" to Phase.PHASE_5,
                        "4000000.01" to Phase.PHASE_6,
                    )
                for ((total, phase) in expected) {
                    val year = PhaseAssessmentReturn(day("2025-01-01"), day("2025-12-31"), BigDecimal(total))
                    val body = env.assess(entity, listOf(year)).body!!
                    assertThat(body.phase).describedAs(total).isEqualTo(phase)
                    assertThat(body.inputs!!.daysCovered).isEqualTo(365)
                }
                val year = PhaseAssessmentReturn(day("2025-01-01"), day("2025-12-31"), BigDecimal("200000"))
                val atLimit = env.assess(entity, listOf(year)).body!!
                assertThat(atLimit.implementationDate).isEqualTo(day("2028-04-01"))
            }
        }
    }

    @Test
    fun `a request with box4Figures only is accepted as before, with both or neither it is 400`() {
        run { env ->
            env.withEntity { _, entity ->
                val figureOnly = env.assess(entity, box4 = BigDecimal("1250000.00")).body!!
                assertThat(figureOnly.phase).isEqualTo(Phase.PHASE_5)
                assertThat(figureOnly.totalAnnualSupplies).isEqualByComparingTo("1250000.00")
                assertThat(figureOnly.inputs!!.summedBox4Figures).isEqualByComparingTo("1250000.00")
                assertThat(figureOnly.inputs!!.extrapolated).isFalse()
                assertThat(figureOnly.inputs!!.returns).isNull()
                val one = quarter("2025-03-31", "1")
                assertThat(refused { env.assess(entity, listOf(one), box4 = BigDecimal.ONE) }.code.httpStatus)
                    .isEqualTo(400)
                assertThat(refused { env.assess(entity) }.code.httpStatus).isEqualTo(400)
                assertThat(refused { env.assess(entity, emptyList()) }.code.httpStatus).isEqualTo(400)
                assertThat(refused { env.assess(entity, box4 = BigDecimal.ONE, extrapolate = true) }.code.httpStatus)
                    .isEqualTo(400)
            }
        }
    }

    @Test
    fun `returns ending in different months are 400 naming the months and no window is guessed`() {
        run { env ->
            env.withEntity { _, entity ->
                val before = env.stored(entity)
                val mixed = listOf(quarter("2025-03-31", "1"), quarter("2025-04-30", "1"))
                val refusal = refused { env.assess(entity, mixed) }
                assertThat(refusal.code.httpStatus).isEqualTo(400)
                assertThat(refusal.detail).contains("MARCH").contains("APRIL")
                assertThat(env.stored(entity)).isEqualTo(before)
            }
        }
    }

    @Test
    fun `with no return ending in 2025 the date is outstanding, the request is 400 and nothing is stored`() {
        run { env ->
            env.withEntity { _, entity ->
                val old = listOf(quarter("2024-12-31", "5000"))
                val outstanding = refused { env.assess(entity, old) }
                assertThat(outstanding.code.httpStatus).isEqualTo(400)
                assertThat(outstanding.code.code).isEqualTo("SRT-001")
                assertThat(outstanding.detail).contains(old.single().periodStart.toString()).contains("2024-12-31")
                assertThat(env.stored(entity)).isNull()
                val backwards = PhaseAssessmentReturn(day("2025-03-31"), day("2025-03-01"), BigDecimal.ONE)
                assertThat(refused { env.assess(entity, listOf(backwards)) }.code.httpStatus).isEqualTo(400)
            }
        }
    }

    @Test
    fun `INV-AC-8587 the stored result equals the inputs returned and a repeat supersedes the earlier one`() {
        run { env ->
            env.withEntity { _, entity ->
                val returns = listOf(quarter("2025-03-31", "70000.50"), quarter("2025-06-30", "20000.25", days = 91))
                val body = env.assess(entity, returns, extrapolate = true).body!!
                val stored = env.stored(entity)!!
                val inputs = body.inputs!!
                assertThat(
                    stored.assessment.inputs.window!!
                        .first
                        .atDay(1),
                ).isEqualTo(inputs.windowStart)
                assertThat(
                    stored.assessment.inputs.window!!
                        .last
                        .atEndOfMonth(),
                ).isEqualTo(inputs.windowEnd)
                assertThat(stored.assessment.inputs.daysCovered).isEqualTo(inputs.daysCovered!!.toLong())
                assertThat(stored.assessment.inputs.extrapolationChosen).isEqualTo(inputs.extrapolated)
                assertThat(stored.assessment.inputs.summedBox4).isEqualByComparingTo(inputs.summedBox4Figures!!)
                assertThat(
                    stored.assessment.inputs.returnsUsed
                        .map { Triple(it.periodStart, it.periodEnd, it.box4) },
                ).containsExactlyElementsOf(
                    inputs.returns!!.map { Triple(it.periodStart, it.periodEnd, it.box4Figures) },
                )
                assertThat(stored.assessment.totalAnnualSupplies).isEqualByComparingTo(body.totalAnnualSupplies!!)
                assertThat(stored.assessment.mandatoryDate).isEqualTo(body.implementationDate)
                env.assess(entity, box4 = BigDecimal("5000000"))
                assertThat(env.stored(entity)!!.assessment.mandatoryDate).isEqualTo(day("2031-04-01"))
            }
        }
    }

    @Test
    fun `an entity that does not exist is 404`() {
        run { env ->
            env.withEntity { _, entity ->
                val missing = entity.copy(entityRef = "no-such-entity")
                assertThat(refused { env.assess(missing, box4 = BigDecimal.ONE) }.code.httpStatus).isEqualTo(404)
            }
        }
    }
}
