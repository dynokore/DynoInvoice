package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.JooqAcknowledgementEvents
import com.dynokore.gst.transport.adapters.persistence.JooqAttemptOwner
import com.dynokore.gst.transport.adapters.persistence.JooqNotificationLedger
import com.dynokore.gst.transport.adapters.persistence.JooqNotificationUnitOfWork
import com.dynokore.gst.transport.adapters.persistence.JooqPollCursorStore
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApNotificationLedger.Companion.AP_NOTIFICATION_LEDGER
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApPollCursor.Companion.AP_POLL_CURSOR
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.application.AttemptOwnerRef
import com.dynokore.gst.transport.application.LedgerKind
import com.dynokore.gst.transport.application.LedgerResult
import com.dynokore.gst.transport.domain.AcknowledgementOutcome
import com.dynokore.gst.transport.domain.AcknowledgementReceived
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The jOOQ implementations of the AP notification ports against the real database, and the bootstrap declaration of
 * the platform-scoped objects (INV-T-12484; rulings INV-Q-14970 A, INV-Q-14973 A). The classes are not beans: they are
 * built from the beans of the started context.
 */
class ApNotificationPersistenceDbTest {
    private companion object {
        const val SELECT_COMMENT = "select obj_description('transport.ap_poll_cursor'::regclass, 'pg_class')"
    }

    private val actor = "svc:transport-svc"
    private val adapter = AdapterId("primary")
    private val nid = "n-${UUID.randomUUID()}"

    private class Ports(
        context: ApplicationContext,
        actor: String,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val ledger = JooqNotificationLedger(dsl)
        val unit = JooqNotificationUnitOfWork(scope, runner, actor)
        val events =
            JooqAcknowledgementEvents(context.getBean(com.dynokore.platform.outboxandqueue.OutboxWriter::class.java))
        val platform: PlatformTenantBinding = context.getBean(PlatformTenantBinding::class.java)
        val owners =
            JooqAttemptOwner(dsl, runner, context.getBean(PlatformTenantBinding::class.java), actor)
        val cursors =
            JooqPollCursorStore(
                dsl,
                runner,
                context.getBean(PlatformTenantBinding::class.java),
                actor,
                context.getBean(Clock::class.java),
            )
    }

    private fun run(
        dir: Path,
        block: (Ports) -> Unit,
    ) {
        TransportContext.online(dir).run { context -> block(Ports(context, actor)) }
    }

    private fun ownerOf(tenant: UUID) = AttemptOwnerRef(TenantRef(tenant.toString()), EntityRef("E-001"))

    private fun Ports.ledgerRows(tenant: UUID): Int =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { dsl.fetchCount(AP_NOTIFICATION_LEDGER) } }

    private fun Ports.outboxRows(tenant: UUID): Int =
        scope.bind(TenantId(tenant), actor) { runner.inTransaction { dsl.fetchCount(Outbox.OUTBOX) } }

    private fun Ports.attempt(
        tenant: UUID,
        doc: UUID,
    ) = scope.bind(TenantId(tenant), actor) {
        runner.inTransaction {
            dsl
                .insertInto(SUBMISSION_ATTEMPT)
                .set(SUBMISSION_ATTEMPT.ID, UUID.randomUUID())
                .set(SUBMISSION_ATTEMPT.TENANT_ID, tenant)
                .set(SUBMISSION_ATTEMPT.ENTITY_REF, "E-001")
                .set(SUBMISSION_ATTEMPT.DOC_UUID, doc)
                .set(SUBMISSION_ATTEMPT.VERSION, 1)
                .set(SUBMISSION_ATTEMPT.ADAPTER_ID, "primary")
                .set(SUBMISSION_ATTEMPT.STARTED_AT, OffsetDateTime.now())
                .execute()
        }
    }

    private fun ack(doc: UUID) = AcknowledgementReceived(adapter, nid, doc, 1, "ack-1", AcknowledgementOutcome.ACCEPTED)

    @Test
    fun `the ledger row has the tenant and entity of the owner and the kind passed, for each kind`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        LedgerKind.entries.forEach { kind ->
            p.unit.run(ownerOf(a)) { p.ledger.record(ownerOf(a), adapter, "$nid-$kind", kind) } shouldBe
                LedgerResult.RECORDED
        }
        val rows =
            p.scope.bind(TenantId(a), actor) {
                p.runner.inTransaction {
                    p.dsl
                        .selectFrom(AP_NOTIFICATION_LEDGER)
                        .fetch()
                        .associate { it.apNotificationId to Triple(it.tenantId, it.entityRef, it.kind) }
                }
            }
        rows shouldBe LedgerKind.entries.associate { "$nid-$it" to Triple(a, "E-001", it.name) }
    }

    @Test
    fun `the same adapter and notification id twice gives one row and answers replay`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        val record = {
            p.unit.run(
                ownerOf(a),
            ) { p.ledger.record(ownerOf(a), adapter, nid, LedgerKind.ACKNOWLEDGEMENT) }
        }
        record() shouldBe LedgerResult.RECORDED
        record() shouldBe LedgerResult.REPLAY
        p.ledgerRows(a) shouldBe 1
    }

    @Test
    fun `the same notification id on two adapters gives two rows`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        listOf("primary", "secondary").forEach {
            p.unit.run(ownerOf(a)) {
                p.ledger.record(ownerOf(a), AdapterId(it), nid, LedgerKind.ACKNOWLEDGEMENT)
            } shouldBe LedgerResult.RECORDED
        }
        p.ledgerRows(a) shouldBe 2
    }

    @Test
    fun `a ledger insert with no bound tenant is refused by row level security`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        shouldThrow<Exception> {
            p.runner.inTransaction { p.ledger.record(ownerOf(a), adapter, nid, LedgerKind.ACKNOWLEDGEMENT) }
        }
        p.ledgerRows(a) shouldBe 0
    }

    @Test
    fun `a ledger row of another tenant is invisible to the bound one`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        p.unit.run(ownerOf(a)) { p.ledger.record(ownerOf(a), adapter, nid, LedgerKind.REJECTION) }
        p.ledgerRows(a) shouldBe 1
        p.ledgerRows(b) shouldBe 0
    }

    @Test
    fun `the attempt owner of a Doc UUID attempted by tenant A is A while tenant B is bound`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val doc = UUID.randomUUID()
        p.attempt(a, doc)
        val owner = p.scope.bind(TenantId(b), actor) { p.owners.ownerOf(DocRef(doc, 1)) }
        owner shouldBe ownerOf(a)
    }

    @Test
    fun `an unknown Doc UUID has no attempt owner`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        p.owners.ownerOf(DocRef(UUID.randomUUID(), 1)).shouldBeNull()
    }

    @Test
    fun `a failure after the ledger insert and before the outbox insert leaves neither row`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        val doc = UUID.randomUUID()
        shouldThrow<IllegalStateException> {
            p.unit.run(ownerOf(a)) {
                p.ledger.record(ownerOf(a), adapter, nid, LedgerKind.ACKNOWLEDGEMENT)
                error("failure before the event")
            }
        }
        p.ledgerRows(a) shouldBe 0
        p.outboxRows(a) shouldBe 0
        // and both together commit
        p.unit.run(ownerOf(a)) {
            p.ledger.record(ownerOf(a), adapter, nid, LedgerKind.ACKNOWLEDGEMENT)
            p.events.publish(ownerOf(a), ack(doc))
        }
        p.ledgerRows(a) shouldBe 1
        p.outboxRows(a) shouldBe 1
    }

    @Test
    fun `the table comment of ap_poll_cursor is the platform-scoped statement`(
        @TempDir dir: Path,
    ) {
        var comment: String? = null
        TransportContext.online(dir).run { context ->
            comment =
                context.getBean(javax.sql.DataSource::class.java).connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(SELECT_COMMENT).use { rows ->
                            rows.next()
                            rows.getString(1)
                        }
                    }
                }
        }
        comment shouldBe "platform-scoped: no tenant data"
    }

    @Test
    fun `a cursor written twice keeps one row with the later position`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        p.cursors.read(adapter).shouldBeNull()
        p.cursors.write(adapter, "c-1")
        p.cursors.write(adapter, "c-2")
        p.cursors.read(adapter) shouldBe "c-2"
        p.platform.bindPlatform(actor) {
            p.runner.inTransaction { p.dsl.fetchCount(AP_POLL_CURSOR, AP_POLL_CURSOR.ADAPTER_ID.eq(adapter.value)) }
        } shouldBe
            1
    }

    @Test
    fun `a unit of work run with the owner of tenant A while tenant B is bound writes under A`(
        @TempDir dir: Path,
    ) = run(dir) { p ->
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        p.scope.bind(TenantId(b), actor) {
            p.unit.run(ownerOf(a)) { p.ledger.record(ownerOf(a), adapter, nid, LedgerKind.ACKNOWLEDGEMENT) }
        }
        p.ledgerRows(a) shouldBe 1
        p.ledgerRows(b) shouldBe 0
    }

    // the bootstrap declaration (INV-Q-14970 A)

    private val real = "transport.adapter_attempt_counts,transport.attempt_owner,transport.ap_poll_cursor"

    private fun startupFailure(
        dir: Path,
        value: String,
    ): String? {
        var failure: Throwable? = null
        TransportContext.online(dir, "inv.tenancy.platform-scoped-tables=$value").run { failure = it.startupFailure }
        return generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }.takeIf {
            failure !=
                null
        }
    }

    @Test
    fun `the platform-scoped tables of the real application yaml are the three names`(
        @TempDir dir: Path,
    ) {
        TransportContext.online(dir).run { context ->
            context.environment
                .getProperty("inv.tenancy.platform-scoped-tables")!!
                .split(",") shouldContainExactly real.split(",")
        }
    }

    @Test
    fun `a list without ap_poll_cursor fails to start naming it`(
        @TempDir dir: Path,
    ) {
        startupFailure(dir, "transport.adapter_attempt_counts,transport.attempt_owner")!! shouldContain
            "transport.ap_poll_cursor"
    }

    @Test
    fun `a look-alike name fails to start`(
        @TempDir dir: Path,
    ) {
        startupFailure(dir, "transport.adapter_attempt_counts,transport.attempt_owner,transport.ap_poll_cursors")
            .shouldNotBeNullValue()
    }

    @Test
    fun `a list that also holds the tenant ledger table fails to start`(
        @TempDir dir: Path,
    ) {
        startupFailure(dir, "$real,transport.ap_notification_ledger").shouldNotBeNullValue()
    }

    private fun String?.shouldNotBeNullValue() {
        (this != null) shouldBe true
    }
}
