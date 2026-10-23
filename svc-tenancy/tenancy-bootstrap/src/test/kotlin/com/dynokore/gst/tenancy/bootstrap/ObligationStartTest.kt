package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.EntityStatusResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.productruntime.ProductEventTypes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

private typealias Declared = SetEntityStatusRequest.ObligationStartBasis
private typealias Basis = EntityStatusResponse.ObligationStartBasis

/**
 * The obligation start (INV-REQ-335, INV-REQ-1312, INV-REQ-717): the service derives it from the registration start
 * date and the implementation date of VP-51 (INV-Q-8733 A); VP-51 is set through config-resolver's writer, the
 * operation the shared setPolicy fronts (INV-Q-8729 A). Test names carry the criterion keys.
 */
class ObligationStartTest {
    private val vp51 = "VP-51"
    private val entityAdmin = "entity.admin"

    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun obligation(
        registration: String? = null,
        from: String = TODAY.toString(),
    ) = SetEntityStatusRequest(
        SetEntityStatusRequest.StatusKind.OBLIGATION_START,
        day(from),
        registrationStartDate = registration?.let(::day),
    )

    private fun refused(block: () -> Unit): ApiRefusal =
        assertThatThrownBy(block).isInstanceOf(ApiRefusal::class.java).actual() as ApiRefusal

    private fun profileEvents(env: ProfileEnv) =
        env.captured.envelopes.count { it.type == ProductEventTypes.ENTITY_PROFILE_CHANGED }

    private fun voluntary(
        env: ProfileEnv,
        group: com.dynokore.gst.tenancy.contract.model.GroupResponse,
        entity: EntityResponse,
        date: String,
    ) = env.setPolicy(
        vp51,
        ScopeLevel.ENTITY,
        group,
        entity,
        "EARLIER_VOLUNTARY_DATE",
        "\"$date\"",
        role = entityAdmin,
    )

    @Test
    fun `INV-AC-1626 INV-AC-8588 an earlier voluntary activation date becomes the implementation date`() {
        run { env ->
            env.withGroup(1) { _, group, members ->
                val entity = members.single()
                env.assess(entity, box4 = BigDecimal("500000"))
                assertThat(env.stored(entity)!!.assessment.mandatoryDate).isEqualTo(day("2029-04-01"))
                voluntary(env, group, entity, "2027-01-01")
                val body = env.set(entity, obligation(registration = "2020-02-01")).body!!
                assertThat(body.obligationStartDate).isEqualTo(day("2027-01-01"))
                assertThat(body.obligationStartBasis).isEqualTo(Basis.EARLIER_VOLUNTARY_DATE)
                assertThat(body.obligationStartOverride).isFalse()
                assertThat(body.registrationStartDate).isEqualTo(day("2020-02-01"))
            }
        }
    }

    @Test
    fun `INV-AC-1627 INV-AC-8588 a registration start later than the implementation date is the obligation start`() {
        run { env ->
            env.withEntity { _, entity ->
                env.assess(entity, box4 = BigDecimal("100000"))
                val later = env.set(entity, obligation(registration = "2028-06-01")).body!!
                assertThat(later.obligationStartDate).isEqualTo(day("2028-06-01"))
                assertThat(later.obligationStartBasis).isEqualTo(Basis.MANDATORY_DATE)
                assertThat(later.registrationStartDate).isEqualTo(day("2028-06-01"))
                env.withEntity { _, other ->
                    env.assess(other, box4 = BigDecimal("100000"))
                    val earlier = env.set(other, obligation(registration = "2020-03-01")).body!!
                    assertThat(earlier.obligationStartDate).isEqualTo(day("2028-04-01"))
                    val none = env.set(other, obligation(from = TODAY.plusDays(1).toString())).body!!
                    assertThat(none.obligationStartDate).isEqualTo(day("2028-04-01"))
                }
            }
        }
    }

    @Test
    fun `INV-AC-2300 INV-AC-8593 a later implementation date is refused, the same and an earlier one are accepted`() {
        run { env ->
            env.withGroup(1) { _, group, members ->
                val entity = members.single()
                // no mandatory date stored yet: nothing bounds the date, so the writer refuses (fail closed)
                val unbounded = assertThatThrownBy { voluntary(env, group, entity, "2027-01-01") }
                unbounded.isInstanceOf(PolicyRefused::class.java)
                env.assess(entity, box4 = BigDecimal("500000"))
                voluntary(env, group, entity, "2027-01-01")
                val later = assertThatThrownBy { voluntary(env, group, entity, "2029-04-02") }
                later.isInstanceOf(PolicyRefused::class.java)
                assertThat((later.actual() as PolicyRefused).refusal).isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
                val scope = PolicyScope.entity(env.tenants.current(), entity.entityRef, group = group.groupRef)
                val kept =
                    env.inTx {
                        env.reader
                            .resolve(VariationPointKey(vp51), scope, TODAY)
                            .selected
                            .single()
                    }
                assertThat(kept.key).isEqualTo("EARLIER_VOLUNTARY_DATE")
                assertThat(kept.valueJson).contains("2027-01-01")
                voluntary(env, group, entity, "2029-04-01")
                voluntary(env, group, entity, "2029-03-31")
                val followed = env.set(entity, obligation()).body!!
                assertThat(followed.obligationStartDate).isEqualTo(day("2029-03-31"))
                assertThat(followed.obligationStartBasis).isEqualTo(Basis.EARLIER_VOLUNTARY_DATE)
            }
        }
    }

    @Test
    fun `a supplied obligation field that differs from the derived value is 400 and one that equals it is accepted`() {
        run { env ->
            env.withEntity { _, entity ->
                env.assess(entity, box4 = BigDecimal("100000"))
                val base = obligation()
                val differs =
                    listOf(
                        base.copy(obligationStartDate = day("2028-04-02")),
                        base.copy(obligationStartBasis = Declared.IRAS_COMMUNICATED_DATE),
                        base.copy(irasReference = "IRAS-1"),
                        base.copy(obligationStartOverride = true),
                    )
                for (request in differs) {
                    assertThat(refused { env.set(entity, request) }.code.httpStatus).isEqualTo(400)
                }
                assertThat(env.queries.history(entity.entityRef, StatusKind.OBLIGATION_START)).isEmpty()
                val equal =
                    base.copy(
                        obligationStartDate = day("2028-04-01"),
                        obligationStartBasis = SetEntityStatusRequest.ObligationStartBasis.MANDATORY_DATE,
                        obligationStartOverride = false,
                    )
                assertThat(env.set(entity, equal).body!!.obligationStartDate).isEqualTo(day("2028-04-01"))
            }
        }
    }

    @Test
    fun `a supplied basis or override is compared by presence, so the former defaults are refused when they differ`() {
        run { env ->
            env.withGroup(1) { _, group, members ->
                val entity = members.single()
                env.assess(entity, box4 = BigDecimal("500000"))
                voluntary(env, group, entity, "2027-01-01")
                val base = obligation()
                val differs =
                    listOf(
                        base.copy(obligationStartBasis = Declared.MANDATORY_DATE),
                        base.copy(obligationStartOverride = true),
                    )
                for (request in differs) {
                    assertThat(refused { env.set(entity, request) }.code.httpStatus).isEqualTo(400)
                }
                assertThat(env.queries.history(entity.entityRef, StatusKind.OBLIGATION_START)).isEmpty()
                val equal =
                    base.copy(obligationStartBasis = Declared.EARLIER_VOLUNTARY_DATE, obligationStartOverride = false)
                assertThat(env.set(entity, equal).body!!.obligationStartDate).isEqualTo(day("2027-01-01"))
            }
        }
    }

    @Test
    fun `INV-AC-768 a member of a GST group with a stored assessment and no VP-51 value is still outstanding`() {
        run { env ->
            env.withGroup(1) { _, _, members ->
                val entity = members.single()
                env.assess(entity, box4 = BigDecimal("100000"))
                val response = env.set(entity, obligation(registration = "2028-06-01"))
                assertThat(response.body!!.obligationStartDate).isNull()
                assertThat(env.queries.history(entity.entityRef, StatusKind.OBLIGATION_START)).isEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-768 a member of a divisional group follows a communicated date with its IRAS reference`() {
        run { env ->
            env.withGroup(1, CreateGroupRequest.GroupKind.DIVISIONAL) { _, group, members ->
                val entity = members.single()
                env.setPolicy(
                    vp51,
                    ScopeLevel.GROUP,
                    group,
                    null,
                    "IRAS_COMMUNICATED_DATE",
                    """{"date":"2028-10-01","iras_reference":"IRAS-REF-4"}""",
                    role = entityAdmin,
                )
                val body = env.set(entity, obligation(registration = "2028-05-01")).body!!
                assertThat(body.obligationStartDate).isEqualTo(day("2028-10-01"))
                assertThat(body.irasReference).isEqualTo("IRAS-REF-4")
                assertThat(body.obligationStartBasis).isEqualTo(Basis.IRAS_COMMUNICATED_DATE)
            }
        }
    }

    @Test
    fun `INV-AC-8588 with no stored mandatory date no obligation start is written and the date stays outstanding`() {
        run { env ->
            env.withEntity { _, entity ->
                val events = profileEvents(env)
                val response = env.set(entity, obligation(registration = "2028-06-01"))
                assertThat(response.body!!.obligationStartDate).isNull()
                assertThat(env.queries.history(entity.entityRef, StatusKind.OBLIGATION_START)).isEmpty()
                assertThat(profileEvents(env)).isEqualTo(events)
            }
        }
    }

    @Test
    fun `INV-AC-768 INV-AC-8591 a group is pre-obligation until IRAS communicates a date, then starts at the later`() {
        run { env ->
            env.withGroup(2) { _, group, members ->
                val (first, second) = members
                assertThat(env.set(first, obligation(registration = "2028-05-01")).body!!.obligationStartDate).isNull()
                assertThat(env.queries.history(first.entityRef, StatusKind.OBLIGATION_START)).isEmpty()
                env.setPolicy(
                    vp51,
                    ScopeLevel.GROUP,
                    group,
                    null,
                    "IRAS_COMMUNICATED_DATE",
                    """{"date":"2028-10-01","iras_reference":"IRAS-REF-9"}""",
                    role = entityAdmin,
                )
                // never derived from the phase table: an assessment of the member does not change the group's date
                env.assess(first, box4 = BigDecimal("9000000"))
                val early = env.set(first, obligation(registration = "2028-05-01")).body!!
                assertThat(early.obligationStartDate).isEqualTo(day("2028-10-01"))
                assertThat(early.obligationStartBasis).isEqualTo(Basis.IRAS_COMMUNICATED_DATE)
                assertThat(early.irasReference).isEqualTo("IRAS-REF-9")
                assertThat(early.obligationStartOverride).isTrue()
                val late = env.set(second, obligation(registration = "2028-12-01")).body!!
                assertThat(late.obligationStartDate).isEqualTo(day("2028-12-01"))
                assertThat(late.irasReference).isEqualTo("IRAS-REF-9")
                val scope = PolicyScope.tenant(env.tenants.current(), group = group.groupRef)
                val record =
                    env.inTx {
                        env.reader
                            .resolve(VariationPointKey(vp51), scope, TODAY)
                            .selected
                            .single()
                    }
                assertThat(record.valueJson).contains("2028-10-01").contains("IRAS-REF-9")
            }
        }
    }
}
