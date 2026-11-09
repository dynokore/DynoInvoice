package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.PeriodFilingStatusHandler
import com.dynokore.gst.tenancy.adapters.http.PackDeltaCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodListCursor
import com.dynokore.gst.tenancy.adapters.http.PeriodsController
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.FilingFactStore
import com.dynokore.gst.tenancy.application.period.ListPeriodRecords
import com.dynokore.gst.tenancy.application.period.PackDeltaPreview
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.RecordExtension
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.PeriodRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * listPeriodRecords (INV-REQ-85, INV-Q-7388) and the replica of PeriodFilingStatusChanged it reads (INV-Q-16113): one
 * state per record by the precedence late, filed, due, open, the filing date of a filed or late period, the extension
 * flag and the position. The clock is fixed at 2026-08-15 in Singapore.
 */
class PeriodListTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/submission/schemas/period-filing-status-changed-payload.schema.json",
                    ),
                ) { "the filing status schema is not on the classpath" }
                    .use { it.readBytes().toString(Charsets.UTF_8) },
            )

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

    private fun PeriodEnv.itemsAt(entity: EntityResponse) =
        controllerAt("2026-08-15T02:00:00Z").listPeriodRecords(entity.entityRef, 100, null).body!!.items

    private fun status(
        entityRef: String,
        period: String,
        recordedAt: String,
        filedAt: String? = null,
        late: Boolean = false,
    ): String {
        val filed = filedAt?.let { ",\"governingFiledAt\":\"$it\"" }.orEmpty()
        return "{\"entityRef\":\"$entityRef\",\"period\":\"$period\",\"submissionLate\":$late," +
            "\"recordedAt\":\"$recordedAt\"$filed}"
    }

    private fun PeriodEnv.handler() =
        PeriodFilingStatusHandler(env.context.getBean(FilingFactStore::class.java), ObjectMapper())

    private fun PeriodEnv.filing(
        tenant: TenantId,
        payload: String,
        valid: Boolean = true,
    ): String {
        if (valid) assertThat(schema.validate(payload, InputFormat.JSON)).describedAs(payload).isEmpty()
        return deliver(tenant, handler(), STATUS, payload)
    }

    private fun run(block: (PeriodEnv, TenantId, EntityResponse) -> Unit) =
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            env.withEntity { tenant, entity -> block(PeriodEnv(env), tenant, entity) }
        }

    private fun summary(r: PeriodRecord) =
        listOf(r.state.value, r.position.value, "${r.filingDueDate}", r.filingDate?.toString(), r.extensionGranted)

    @Test
    fun `INV-AC-16068 the six records show one state each, the filing date of a late period and the extension flag`() {
        run { period, tenant, entity ->
            period.insertRecord(entity, record("2026-04", "2026-04-01", "2026-04-30", "2026-05-31"))
            period.insertRecord(entity, record("2026-05", "2026-05-01", "2026-05-31", "2026-06-30"))
            period.insertRecord(entity, record("2026-06", "2026-06-01", "2026-06-30", "2026-07-31"))
            period.insertRecord(entity, record("2026-07", "2026-07-01", "2026-07-31", "2026-08-31"))
            period.insertRecord(entity, record("2026-08", "2026-08-01", "2026-08-31", "2026-09-30"))
            period.insertRecord(entity, record("2026-09", "2026-09-01", "2026-09-30", "2026-10-31"))
            period.insertRecord(entity, record("2026-10", "2026-10-01", "2026-10-31", "2026-11-30"))
            period.actAs(entity, "tax.approver")
            period.extend(entity, "2026-05", extension("2026-07-07"))
            period.filing(tenant, status(entity.entityRef, "2026-05", "2026-07-10T04:00:00Z", "2026-07-10T03:00:00Z"))
            period.filing(tenant, status(entity.entityRef, "2026-06", "2026-07-20T04:00:00Z", "2026-07-20T03:00:00Z"))
            val items = period.itemsAt(entity)
            assertThat(items.map { it.periodRef })
                .containsExactly("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09")
            assertThat(items.map(::summary))
                .containsExactly(
                    listOf("LATE", "PAST", "2026-05-31", null, false),
                    listOf("LATE", "PAST", "2026-07-07", "2026-07-10", true),
                    listOf("FILED", "PAST", "2026-07-31", "2026-07-20", false),
                    listOf("DUE", "PAST", "2026-08-31", null, false),
                    listOf("OPEN", "CURRENT", "2026-09-30", null, false),
                    listOf("OPEN", "NEXT", "2026-10-31", null, false),
                )
        }
    }

    @Test
    fun `the filing date is the Singapore calendar date of governingFiledAt`() {
        run { period, tenant, entity ->
            period.insertRecord(entity, record("2026-06", "2026-06-01", "2026-06-30", "2026-07-31"))
            period.filing(tenant, status(entity.entityRef, "2026-06", "2026-07-20T04:00:00Z", "2026-07-19T17:30:00Z"))
            val item = period.itemsAt(entity).single()
            assertThat(item.filingDate.toString()).isEqualTo("2026-07-20")
            assertThat(item.state.value).isEqualTo("FILED")
        }
    }

    @Test
    fun `documents past their submission due date make a filed period late`() {
        run { period, tenant, entity ->
            period.insertRecord(entity, record("2026-06", "2026-06-01", "2026-06-30", "2026-07-31"))
            val late = status(entity.entityRef, "2026-06", "2026-07-20T04:00:00Z", "2026-07-20T03:00:00Z", late = true)
            period.filing(tenant, late)
            val item = period.itemsAt(entity).single()
            assertThat(item.state.value).isEqualTo("LATE")
            assertThat(item.filingDate.toString()).isEqualTo("2026-07-20")
        }
    }

    @Test
    fun `the event with the greatest recordedAt is kept, and a payload without governingFiledAt is no filing fact`() {
        run { period, tenant, entity ->
            period.insertRecord(entity, record("2026-06", "2026-06-01", "2026-06-30", "2026-07-31"))
            val at = period.controllerAt("2026-08-15T02:00:00Z")
            val first = status(entity.entityRef, "2026-06", "2026-07-20T04:00:00Z", "2026-07-20T03:00:00Z")
            assertThat(period.filing(tenant, first)).isEqualTo("APPLIED")
            // an older recordedAt changes nothing
            val older = status(entity.entityRef, "2026-06", "2026-07-19T04:00:00Z")
            assertThat(period.filing(tenant, older)).isEqualTo("IGNORED")
            assertThat(
                at
                    .listPeriodRecords(entity.entityRef, 100, null)
                    .body!!
                    .items
                    .single()
                    .filingDate
                    .toString(),
            ).isEqualTo("2026-07-20")
            // a newer one without governingFiledAt removes the filing fact
            val newer = status(entity.entityRef, "2026-06", "2026-07-21T04:00:00Z")
            assertThat(period.filing(tenant, newer)).isEqualTo("APPLIED")
            val item =
                at
                    .listPeriodRecords(entity.entityRef, 100, null)
                    .body!!
                    .items
                    .single()
            assertThat(item.filingDate).isNull()
            assertThat(item.state.value).isEqualTo("LATE")
        }
    }

    @Test
    fun `a payload missing period, or with a date-only recordedAt, breaks the contract and stores nothing`() {
        run { period, tenant, entity ->
            val noPeriod =
                "{\"entityRef\":\"${entity.entityRef}\",\"submissionLate\":false," +
                    "\"recordedAt\":\"2026-07-20T04:00:00Z\"}"
            assertThat(schema.validate(noPeriod, InputFormat.JSON)).isNotEmpty()
            assertThatThrownBy { period.filing(tenant, noPeriod, valid = false) }
                .isInstanceOf(ContractViolation::class.java)
            val dateOnly = status(entity.entityRef, "2026-06", "2026-07-20")
            assertThatThrownBy { period.filing(tenant, dateOnly, valid = false) }
                .isInstanceOf(ContractViolation::class.java)
        }
    }

    @Test
    fun `a limit of 0, -1 or 501 and a cursor that is not a token are refused with 400`() {
        run { period, _, entity ->
            val at = period.controllerAt("2026-08-15T02:00:00Z")
            listOf(0 to null, -1 to null, 501 to null, 100 to "cursor-1").forEach { (limit, cursor) ->
                val refusal = refusalOf { at.listPeriodRecords(entity.entityRef, limit, cursor) }
                assertThat(refusal).describedAs("$limit $cursor").isNotNull()
            }
            assertThat(refusalOf { at.listPeriodRecords("ENT-UNKNOWN", 100, null) }).isNotNull()
        }
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun PeriodEnv.sixRecords(entity: EntityResponse) =
        (1..6).forEach {
            val month = "2026-${two(it)}"
            insertRecord(entity, record(month, "$month-01", "$month-28", "2026-${two(it + 1)}-28"))
        }

    @Test
    fun `a list longer than the limit is paged by a signed cursor and every record is seen once`() {
        run { period, _, entity ->
            period.sixRecords(entity)
            val at = period.controllerAt("2026-08-15T02:00:00Z")
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = at.listPeriodRecords(entity.entityRef, 4, cursor).body!!
                seen += page.items.map { it.periodRef }
                cursor = page.nextCursor
                pages++
            } while (cursor != null && pages < 5)
            assertThat(seen).containsExactly("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06")
            assertThat(pages).isEqualTo(2)
        }
    }

    @Test
    fun `a cursor of another user, an expired cursor and a tampered cursor are refused and the key is needed`() {
        run { period, _, entity ->
            period.sixRecords(entity)
            val at = period.controllerAt("2026-08-15T02:00:00Z")
            val cursor = at.listPeriodRecords(entity.entityRef, 2, null).body!!.nextCursor!!
            assertThat(
                at
                    .listPeriodRecords(entity.entityRef, 2, cursor)
                    .body!!
                    .items
                    .map { it.periodRef },
            ).containsExactly("2026-03", "2026-04")
            val later = period.controllerAt("2026-08-16T02:00:01Z")
            assertThat(refusalOf { later.listPeriodRecords(entity.entityRef, 2, cursor) }).isNotNull()
            val tampered = cursor.dropLast(1) + (if (cursor.last() == 'A') 'B' else 'A')
            assertThat(refusalOf { at.listPeriodRecords(entity.entityRef, 2, tampered) }).isNotNull()
            period.people.caller = "identity:USR-OTHER"
            assertThat(refusalOf { at.listPeriodRecords(entity.entityRef, 2, cursor) }).isNotNull()
        }
    }

    private companion object {
        const val STATUS = "com.dynokore.gst.submission.PeriodFilingStatusChanged.v1"
    }
}
