package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackActivatedHandler
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackStagedHandler
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.PackDeltaCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodListCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodsController
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.ListPeriodRecords
import com.dynokore.gst.tenancy.application.period.PackActivationRecompute
import com.dynokore.gst.tenancy.application.period.PackDeltaPreview
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.RecordExtension
import com.dynokore.gst.tenancy.contract.model.CalendarPackDeltaResponse
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * getCalendarPackDelta over PostgreSQL with the real application context (INV-REQ-88, owner answer INV-Q-16810 C): the
 * pack is staged through the RulePackStaged consumer, the caller is decided by the permission key
 * `calendar-pack-delta.view` through the replica, and the read writes no row and publishes no event.
 */
class PackDeltaApiTest {
    private val mapper = ObjectMapper()
    private val key = PeriodPermissions.PACK_DELTA_VIEW

    private fun contents(pinned: String? = null) =
        """
        {"windows":{"QUARTERLY":[
          {"start":"2026-01-01","end":"2026-03-31","returnForm":"F5"},
          {"start":"2026-04-01","end":"2026-06-30","returnForm":"F5"},
          {"start":"2026-07-01","end":"2026-09-30","returnForm":"F5"},
          {"start":"2026-10-01","end":"2026-12-31","returnForm":"F5"}]},
         "finalReturnForm":"F8",
         "dueRules":{"F5":{"monthsAfterEnd":1,"endOfMonth":true,"daysAfterEnd":0},
                     "F8":{"monthsAfterEnd":3,"endOfMonth":true,"daysAfterEnd":0}},
         ${pinned?.let { "\"pinnedRule\":\"$it\"," }.orEmpty()}
         "weekend":["SATURDAY","SUNDAY"],"holidays":[]}
        """.trimIndent()

    private fun staged(
        version: String,
        from: String = "2026-01-01",
        packType: String = "calendar",
        pinned: String? = null,
    ) = "{\"packIdentifier\":\"sg-calendar\",\"packType\":\"$packType\",\"version\":\"$version\"," +
        "\"effectiveFrom\":\"$from\",\"minEngineVersion\":\"1.0.0\"," +
        "\"artefact\":{\"manifest\":{},\"checksums\":{},\"signature\":\"s\",\"contents\":${contents(pinned)}}}"

    private fun activated(
        version: String,
        from: String,
    ) = "{\"packIdentifier\":\"sg-calendar\",\"version\":\"$version\",\"effectiveFrom\":\"$from\"}"

    private inner class Feed(
        val env: ProfileEnv,
        val tenant: TenantId,
        context: AssertableWebApplicationContext,
    ) {
        private val replica = context.getBean(CalendarPackReplica::class.java)
        private val effects = context.getBean(PackActivationRecompute::class.java)
        private val stagedHandler: EventHandler =
            CalendarPackStagedHandler(
                replica,
                mapper,
                context.getBean(CalendarPackDecoder::class.java),
                effects = effects,
            )
        private val activatedHandler: EventHandler = CalendarPackActivatedHandler(replica, mapper, effects = effects)

        fun stage(data: String) = deliver(stagedHandler, CalendarPackStagedHandler.STAGED, data)

        fun activate(data: String) = deliver(activatedHandler, CalendarPackActivatedHandler.ACTIVATED, data)

        private fun deliver(
            handler: EventHandler,
            type: String,
            data: String,
        ) = env.inTenant(tenant) { env.inTx { handler.handle(testEnvelope(type, tenant, data)).name } }
    }

    /** An entity registered on quarterly periods under pack 2026.1, with 2026.2 (pins next working day) staged. */
    private fun scenario(block: (PeriodEnv, Feed, EntityResponse) -> Unit) {
        BoundaryFixture
            .runner()
            .withPropertyValues("spring.profiles.active=prod")
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                env.withEntity { tenant, entity ->
                    val period = PeriodEnv(env)
                    val feed = Feed(env, tenant, context)
                    feed.stage(staged("2026.1"))
                    feed.activate(activated("2026.1", "2026-01-01"))
                    period.register(entity, "2026-01-01")
                    period.change(entity, Freq.QUARTERLY, "2026-01-01")
                    feed.stage(staged("2026.2", from = "2026-10-01", pinned = "NEXT_WORKING_DAY"))
                    block(period, feed, entity)
                }
            }
    }

    @Suppress("LongParameterList") // each parameter varies one probe of the operation
    private fun PeriodEnv.delta(
        entity: EntityResponse,
        limit: Int = 100,
        cursor: String? = null,
        version: String = "2026.2",
        pack: String = "sg-calendar",
        api: PeriodsController = periodsApi,
        entityRef: String = entity.entityRef,
    ): CalendarPackDeltaResponse = api.getCalendarPackDelta(entityRef, pack, version, limit, cursor).body!!

    private fun codeOf(block: () -> Unit): ClosedErrorCode? = (refusalOf(block) as? ApiRefusal)?.code

    private fun PeriodEnv.state(entity: EntityResponse) =
        listOf(rows(entity), table(entity, "period_extension", "entity_id"), env.captured.envelopes.size)

    private fun PeriodEnv.controllerAt(instant: String): PeriodsController {
        val clock =
            DefaultListableBeanFactory().apply {
                registerSingleton("clock", Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
            }
        val c = env.context
        return PeriodsController(
            c.getBean(ConfirmPeriods::class.java),
            c.getBean(RecordExtension::class.java),
            c.getBean(ListPeriodRecords::class.java),
            c.getBean(PeriodListCursor::class.java),
            c.getBean(CallerSource::class.java),
            clock.getBeanProvider(Clock::class.java),
            c.getBean(PeriodAccess::class.java),
            c.getBean(PackDeltaPreview::class.java),
            c.getBean(PackDeltaCursor::class.java),
        )
    }

    @Test
    fun `INV-AC-117 the delta before approval lists the two moving periods and nothing changes`() {
        scenario { period, feed, entity ->
            val records = period.records(entity)
            val q3 = records.single { it.start == day("2026-07-01") }.periodRef
            val q4 = records.single { it.start == day("2026-10-01") }.periodRef
            period.actAs(entity, "tax.approver")
            val before = period.state(entity)
            val eventsBefore = period.events(PERIOD_DUE_DATE_CHANGED).size
            val response = period.delta(entity)
            assertThat(response.entityRef).isEqualTo(entity.entityRef)
            assertThat(response.packIdentifier).isEqualTo("sg-calendar")
            assertThat(response.version).isEqualTo("2026.2")
            assertThat(response.nextCursor).isNull()
            assertThat(response.items.map { listOf(it.periodRef, "${it.oldFilingDueDate}", "${it.newFilingDueDate}") })
                .isEqualTo(
                    listOf(
                        listOf(q3, "2026-10-31", "2026-11-02"),
                        listOf(q4, "2027-01-31", "2027-02-01"),
                    ).sortedBy { it[0] },
                )
            assertThat(period.state(entity)).isEqualTo(before)
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).hasSize(eventsBefore)
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
            assertThat(feed.activate(activated("2026.2", "2026-10-01"))).isEqualTo("APPLIED")
            assertThat(period.delta(entity).items).isEmpty()
            val changed = period.events(PERIOD_DUE_DATE_CHANGED)
            assertThat(changed.map { it["period"].asString() }).containsExactlyInAnyOrder(q3, q4)
        }
    }

    private val bad = ClosedErrorCode.CONTRACT_VIOLATION

    private fun PeriodEnv.assertKeyGate(entity: EntityResponse) {
        DeploymentForm.entries.forEach { form ->
            val cells = RolePermissions.cells(form)
            assertThat(cells.getValue("tax.approver")[key]).isEqualTo(RoleAllowance.PERMITTED)
            listOf("entity.admin", "tenant.admin", "tax.operator").forEach {
                assertThat(cells.getValue(it)[key])
                    .describedAs("$it in $form")
                    .isNotEqualTo(RoleAllowance.PERMITTED)
            }
        }
        listOf("entity.admin", "tenant.admin", "tax.operator").forEach { role ->
            actAs(entity, role)
            val refusal = refusalOf { delta(entity) } as ApiRefusal
            assertThat(refusal.code).describedAs(role).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            // the key gate runs before every parameter check: a bad limit is still 403 for this caller
            assertThat(
                codeOf {
                    delta(entity, limit = 501)
                },
            ).describedAs("$role limit 501").isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(
                codeOf { delta(entity, limit = 0) },
            ).describedAs("$role limit 0").isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            listOf("2026-10-31", "2026-11-02", "2027-01-31", "2027-02-01").forEach {
                assertThat("${refusal.message} ${refusal.detail}").doesNotContain(it)
            }
        }
        actAs(entity, "tax.approver")
    }

    private fun PeriodEnv.assertNotFound(
        feed: Feed,
        entity: EntityResponse,
    ) {
        assertThat(codeOf { delta(entity, version = "9.9") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
        assertThat(feed.stage(staged("9.8", packType = "scope"))).isEqualTo("IGNORED")
        assertThat(codeOf { delta(entity, version = "9.8") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
        assertThat(codeOf { delta(entity, pack = "other") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
        env.context
            .getBean(TestPeople::class.java)
            .grant(PeriodEnv.PERIOD_CALLER, "ENT-UNKNOWN", "tax.approver")
        assertThat(codeOf { delta(entity, entityRef = "ENT-UNKNOWN") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
    }

    private fun PeriodEnv.assertBadParameters(entity: EntityResponse) {
        assertThat(codeOf { delta(entity, pack = "") }).isEqualTo(bad)
        assertThat(codeOf { delta(entity, pack = " ") }).isEqualTo(bad)
        assertThat(codeOf { delta(entity, version = "") }).isEqualTo(bad)
        assertThat(codeOf { delta(entity, version = "  ") }).isEqualTo(bad)
        assertThat(codeOf { delta(entity, limit = 0) }).isEqualTo(bad)
        assertThat(codeOf { delta(entity, limit = 501) }).isEqualTo(bad)
        assertThat(delta(entity, limit = 500).items).hasSize(2)
    }

    private fun PeriodEnv.assertPagesAndCursors(entity: EntityResponse) {
        val full = delta(entity).items
        val first = delta(entity, limit = 1)
        assertThat(first.items).hasSize(1)
        val cursor = checkNotNull(first.nextCursor)
        val second = delta(entity, limit = 1, cursor = cursor)
        assertThat(second.items.single().periodRef).isGreaterThan(first.items.single().periodRef)
        assertThat(second.nextCursor).isNull()
        assertThat(first.items + second.items).isEqualTo(full)
        val tag = cursor.substringAfterLast('.')
        val tampered = cursor.removeSuffix(tag) + (if (tag.first() == 'A') 'B' else 'A') + tag.drop(1)
        assertThat(codeOf { delta(entity, limit = 1, cursor = tampered) }).isEqualTo(bad)
        assertThat(codeOf { delta(entity, limit = 1, cursor = cursor, version = "2026.1") }).isEqualTo(bad)
        val late = controllerAt(Instant.now().plusSeconds(25 * 3600L).toString())
        assertThat(codeOf { delta(entity, limit = 1, cursor = cursor, api = late) }).isEqualTo(bad)
    }

    @Test
    fun `INV-AC-16824 the key, not staged, unknown entity, bad parameters and cursors are refused`() {
        scenario { period, feed, entity ->
            period.assertKeyGate(entity)
            period.assertNotFound(feed, entity)
            period.assertBadParameters(entity)
            period.assertPagesAndCursors(entity)
            // another tenant's entity is not reachable (row-level security)
            val other = period.env.withTenant { _ -> codeOf { period.delta(entity) } }
            assertThat(other).isEqualTo(ClosedErrorCode.NOT_FOUND)
        }
    }
}
