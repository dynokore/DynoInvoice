package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackActivatedHandler
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackStagedHandler
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.adapters.persistence.JooqCalendarPackSource
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.CalendarPackSource
import com.dynokore.gst.tenancy.application.period.PackActivationRecompute
import com.dynokore.gst.tenancy.application.period.PackDeltaPreview
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * The production binding of the port `CalendarPackSource` (INV-T-4930, INV-Q-9725 A; INV-AC-110 part): the replica fed
 * by RulePackStaged and RulePackActivated, a packType equal to `calendar`, `PackRegistry.resolve` for the version in
 * force, and 422 POL-014 "calendar pack unknown" with nothing stored when no pack applies.
 */
class CalendarPackBindingTest {
    private val mapper = ObjectMapper()

    private fun contents(
        returnForm: String = "F5",
        pinned: String? = null,
    ) = """
        {"windows":{"QUARTERLY":[
          {"start":"2026-01-01","end":"2026-03-31","returnForm":"$returnForm"},
          {"start":"2026-04-01","end":"2026-06-30","returnForm":"$returnForm"},
          {"start":"2026-07-01","end":"2026-09-30","returnForm":"$returnForm"},
          {"start":"2026-10-01","end":"2026-12-31","returnForm":"$returnForm"}]},
         "finalReturnForm":"F8",
         "dueRules":{"F5":{"monthsAfterEnd":1,"endOfMonth":true,"daysAfterEnd":0},
                     "F8":{"monthsAfterEnd":3,"endOfMonth":true,"daysAfterEnd":0}},
         ${pinned?.let { "\"pinnedRule\":\"$it\"," }.orEmpty()}
         "weekend":["SATURDAY","SUNDAY"],"holidays":[]}
        """.trimIndent()

    private fun staged(
        version: String,
        packType: String = "calendar",
        returnForm: String = "F5",
        pinned: String? = null,
    ) = "{\"packIdentifier\":\"sg-calendar\",\"packType\":\"$packType\",\"version\":\"$version\"," +
        "\"effectiveFrom\":\"2026-01-01\",\"minEngineVersion\":\"1.0.0\"," +
        "\"artefact\":{\"manifest\":{},\"checksums\":{},\"signature\":\"s\",\"contents\":${contents(
            returnForm,
            pinned,
        )}}}"

    private fun activated(
        version: String,
        from: String,
    ) = "{\"packIdentifier\":\"sg-calendar\",\"version\":\"$version\",\"effectiveFrom\":\"$from\"}"

    private fun envelope(
        type: String,
        tenant: TenantId,
        data: String,
    ) = Envelope(
        EventId(UUID.randomUUID().toString()),
        "/gst/rules-svc",
        type,
        "sg-calendar",
        Instant.now(),
        tenant,
        EntityRef("sg-calendar"),
        null,
        null,
        null,
        "test-0",
        CorrelationId("corr-${UUID.randomUUID()}"),
        CausationId("cause-${UUID.randomUUID()}"),
        data,
    )

    private inner class Feed(
        val env: ProfileEnv,
        val tenant: TenantId,
        context: AssertableWebApplicationContext,
    ) {
        private val replica = context.getBean(CalendarPackReplica::class.java)
        private val decoder = context.getBean(CalendarPackDecoder::class.java)
        private val stagedHandler: EventHandler =
            CalendarPackStagedHandler(
                replica,
                mapper,
                decoder,
                effects = context.getBean(PackActivationRecompute::class.java),
            )
        private val activatedHandler: EventHandler =
            CalendarPackActivatedHandler(
                replica,
                mapper,
                effects = context.getBean(PackActivationRecompute::class.java),
            )

        fun stage(data: String) = deliver(stagedHandler, CalendarPackStagedHandler.STAGED, data)

        fun activate(data: String) = deliver(activatedHandler, CalendarPackActivatedHandler.ACTIVATED, data)

        private fun deliver(
            handler: EventHandler,
            type: String,
            data: String,
        ) = env.inTenant(tenant) { env.inTx { handler.handle(envelope(type, tenant, data)).name } }
    }

    private fun run(block: (PeriodEnv, Feed, com.dynokore.gst.tenancy.contract.model.EntityResponse) -> Unit) {
        BoundaryFixture
            .runner()
            .withPropertyValues("spring.profiles.active=prod")
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                env.withEntity { tenant, entity -> block(PeriodEnv(env), Feed(env, tenant, context), entity) }
            }
    }

    private fun PeriodEnv.assertUnknown(entityRefOf: () -> Unit) {
        val refusal = assertThatThrownBy { entityRefOf() }
        refusal.isInstanceOf(ConstraintRefusal::class.java)
        assertThat((refusal.actual() as ConstraintRefusal).detail).isEqualTo("calendar pack unknown")
    }

    @Test
    fun `the production port is the replica source`() {
        run { period, _, _ ->
            assertThat(period.env.context.getBean(CalendarPackSource::class.java))
                .isInstanceOf(JooqCalendarPackSource::class.java)
        }
    }

    @Test
    fun `INV-AC-110 with a calendar pack staged and activated the initial frequency generates periods from it`() {
        run { period, feed, entity ->
            assertThat(feed.stage(staged("2026.1"))).isEqualTo("APPLIED")
            assertThat(feed.activate(activated("2026.1", "2026-01-01"))).isEqualTo("APPLIED")
            period.register(entity, "2026-01-01")
            period.change(entity, Freq.QUARTERLY, "2026-01-01")
            val records = period.records(entity)
            assertThat(records.map { it.start.toString() })
                .containsExactly("2026-01-01", "2026-04-01", "2026-07-01", "2026-10-01")
            assertThat(records.first().returnForm).isEqualTo("F5")
            assertThat(records.first().dueDate.toString()).isEqualTo("2026-04-30")
        }
    }

    @Test
    fun `INV-AC-110 a later activated version changes the periods by data alone`() {
        run { period, feed, entity ->
            feed.stage(staged("2026.1"))
            feed.stage(staged("2026.2", returnForm = "F8"))
            feed.activate(activated("2026.1", "2026-01-01"))
            feed.activate(activated("2026.2", "2026-06-01"))
            val source = period.env.context.getBean(CalendarPackSource::class.java)
            feed.env.inTenant(feed.tenant) {
                feed.env.inTx {
                    assertThat(source.inForce(day("2026-05-31"))!!.version).isEqualTo("2026.1")
                    assertThat(source.inForce(day("2026-06-01"))!!.version).isEqualTo("2026.2")
                    assertThat(source.inForce(day("2025-12-31"))).isNull()
                }
            }
        }
    }

    @Test
    fun `a pack staged but never activated, a pack of another packType and no pack all refuse POL-014`() {
        run { period, feed, entity ->
            period.register(entity, "2026-01-01")
            period.assertUnknown { period.change(entity, Freq.QUARTERLY, "2026-01-01") }
            assertThat(feed.stage(staged("2026.1"))).isEqualTo("APPLIED")
            period.assertUnknown { period.change(entity, Freq.QUARTERLY, "2026-01-01") }
            assertThat(feed.stage(staged("9.9", packType = "Calendar"))).isEqualTo("IGNORED")
            assertThat(feed.stage(staged("9.8", packType = "scope"))).isEqualTo("IGNORED")
            feed.activate(activated("9.9", "2026-01-01"))
            feed.activate(activated("9.8", "2026-01-01"))
            period.assertUnknown { period.change(entity, Freq.QUARTERLY, "2026-01-01") }
            assertThat(period.rows(entity)).isEmpty()
        }
    }

    @Test
    fun `a staged payload the decoder refuses stores nothing`() {
        run { _, feed, _ ->
            val broken = staged("2026.1").replace("\"finalReturnForm\":\"F8\",", "")
            assertThatThrownBy { feed.stage(broken) }.hasMessageContaining("calendar pack refused")
        }
    }

    private fun PeriodEnv.q3Due(entity: com.dynokore.gst.tenancy.contract.model.EntityResponse) =
        records(entity).single { it.start == day("2026-07-01") }.dueDate.toString()

    @Test
    fun `INV-AC-110 the activation of a pack that pins next working day moves the Saturday due date by data alone`() {
        run { period, feed, entity ->
            feed.stage(staged("2026.1"))
            feed.activate(activated("2026.1", "2026-01-01"))
            period.register(entity, "2026-01-01")
            period.change(entity, Freq.QUARTERLY, "2026-01-01")
            assertThat(period.q3Due(entity)).isEqualTo("2026-10-31")
            val q3 = period.records(entity).single { it.start == day("2026-07-01") }.periodRef
            feed.stage(staged("2026.2", pinned = "NEXT_WORKING_DAY"))
            // staged only: nothing moves yet
            assertThat(period.q3Due(entity)).isEqualTo("2026-10-31")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
            assertThat(feed.activate(activated("2026.2", "2026-10-01"))).isEqualTo("APPLIED")
            assertThat(period.q3Due(entity)).isEqualTo("2026-11-02")
            // Q3 (Saturday 31 Oct) and Q4 (Sunday 31 Jan 2027) are the two unfiled periods due on a non-business day
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).hasSize(2)
            val event = period.events(PERIOD_DUE_DATE_CHANGED).single { it["period"].asString() == q3 }
            assertThat(event["oldFilingDueDate"].asString()).isEqualTo("2026-10-31")
            assertThat(event["newFilingDueDate"].asString()).isEqualTo("2026-11-02")
            assertThat(event["cause"]["packIdentifier"].asString()).isEqualTo("sg-calendar")
            assertThat(event["cause"]["packVersion"].asString()).isEqualTo("2026.2")
            // the same activation delivered again changes nothing
            assertThat(feed.activate(activated("2026.2", "2026-10-01"))).isEqualTo("IGNORED")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).hasSize(2)
        }
    }

    @Test
    fun `the dry-run delta of a staged release lists the due dates that move and changes nothing`() {
        run { period, feed, entity ->
            feed.stage(staged("2026.1"))
            feed.activate(activated("2026.1", "2026-01-01"))
            period.register(entity, "2026-01-01")
            period.change(entity, Freq.QUARTERLY, "2026-01-01")
            feed.stage(
                staged(
                    "2026.2",
                    pinned = "NEXT_WORKING_DAY",
                ).replace("\"effectiveFrom\":\"2026-01-01\"", "\"effectiveFrom\":\"2026-10-01\""),
            )
            val rowsBefore = period.rows(entity)
            val eventsBefore = period.env.captured.envelopes.size
            val preview = period.env.context.getBean(PackDeltaPreview::class.java)
            val delta = feed.env.inTenant(feed.tenant) { preview.delta("sg-calendar", "2026.2") }!!
            assertThat(
                delta.map { listOf(it.entityRef, it.oldFilingDueDate.toString(), it.newFilingDueDate.toString()) },
            ).containsExactly(
                listOf(entity.entityRef, "2026-10-31", "2026-11-02"),
                listOf(entity.entityRef, "2027-01-31", "2027-02-01"),
            )
            assertThat(period.rows(entity)).isEqualTo(rowsBefore)
            assertThat(period.env.captured.envelopes.size).isEqualTo(eventsBefore)
            assertThat(feed.env.inTenant(feed.tenant) { preview.delta("sg-calendar", "9.9") }).isNull()
            assertThat(feed.env.inTenant(feed.tenant) { preview.delta("other", "2026.2") }).isNull()
        }
    }

    private fun scenario(block: (PeriodEnv, Feed, com.dynokore.gst.tenancy.contract.model.EntityResponse) -> Unit) {
        run { period, feed, entity ->
            feed.stage(staged("2026.1"))
            feed.activate(activated("2026.1", "2026-01-01"))
            period.register(entity, "2026-01-01")
            period.change(entity, Freq.QUARTERLY, "2026-01-01")
            assertThat(period.q3Due(entity)).isEqualTo("2026-10-31")
            block(period, feed, entity)
        }
    }

    private val pinnedRelease get() = staged("2026.2", pinned = "NEXT_WORKING_DAY")
    private val release2Activation get() = activated("2026.2", "2026-10-01")

    /** Q3 (Saturday 31 Oct) and Q4 (Sunday 31 Jan 2027) move: two PeriodDueDateChanged, whichever event came first. */
    private fun PeriodEnv.assertMovedOnce(entity: com.dynokore.gst.tenancy.contract.model.EntityResponse) {
        assertThat(q3Due(entity)).isEqualTo("2026-11-02")
        assertThat(events(PERIOD_DUE_DATE_CHANGED)).hasSize(2)
    }

    @Test
    fun `INV-Q-16814 staged then activated moves the due dates once`() {
        scenario { period, feed, entity ->
            feed.stage(pinnedRelease)
            feed.activate(release2Activation)
            period.assertMovedOnce(entity)
        }
    }

    @Test
    fun `INV-Q-16814 activated then staged gives the same result`() {
        scenario { period, feed, entity ->
            assertThat(feed.activate(release2Activation)).isEqualTo("APPLIED")
            assertThat(period.q3Due(entity)).isEqualTo("2026-10-31")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
            assertThat(feed.stage(pinnedRelease)).isEqualTo("APPLIED")
            period.assertMovedOnce(entity)
        }
    }

    @Test
    fun `INV-Q-16814 activated twice and staged twice publish nothing further`() {
        scenario { period, feed, entity ->
            feed.activate(release2Activation)
            feed.stage(pinnedRelease)
            assertThat(feed.activate(release2Activation)).isEqualTo("IGNORED")
            assertThat(feed.stage(pinnedRelease)).isEqualTo("IGNORED")
            period.assertMovedOnce(entity)
        }
    }

    @Test
    fun `INV-Q-16814 staged and never activated, and another version of the same pack, change nothing`() {
        scenario { period, feed, entity ->
            feed.stage(pinnedRelease)
            assertThat(period.q3Due(entity)).isEqualTo("2026-10-31")
            feed.activate(activated("2026.9", "2026-10-01"))
            feed.stage(staged("2026.8", pinned = "NEXT_WORKING_DAY"))
            assertThat(period.q3Due(entity)).isEqualTo("2026-10-31")
            assertThat(period.events(PERIOD_DUE_DATE_CHANGED)).isEmpty()
        }
    }

    @Test
    fun `INV-Q-16814 a superseded activation recomputes nothing in either order`() {
        // 2026.3 (no pin) is activated for the same date as 2026.2 (pins the rule) and was activated later
        scenario { period, feed, entity ->
            feed.stage(pinnedRelease)
            feed.stage(staged("2026.3"))
            feed.activate(activated("2026.3", "2026-10-01"))
            val after = period.q3Due(entity)
            feed.activate(release2Activation)
            val first = period.q3Due(entity)
            assertThat(first).isEqualTo(after).isEqualTo("2026-10-31")
        }
    }
}
