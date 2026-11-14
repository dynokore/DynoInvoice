package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.adapters.events.PackCurrencyChangedHandler
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Inbox.Companion.INBOX
import com.dynokore.gst.notification.application.alerting.PackAlertNotDelivered
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationContext
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class Log : EventLogReader {
    private val records = mutableListOf<LogRecord>()

    fun add(envelopeJson: String) {
        records += LogRecord(records.size + 1L, envelopeJson)
    }

    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
}

class Boundary(
    context: ApplicationContext,
    topic: String,
) {
    val scope: TenantScope = context.getBean(TenantScope::class.java)
    val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
    val dsl: DSLContext = context.getBean(DSLContext::class.java)
    val log = Log()
    val structure: StructureReplica = context.getBean(StructureReplica::class.java)
    private var structureVersion = 0L

    /** Feeds [tenants] as TENANT nodes (retired as given) and completes the snapshot. */
    fun register(
        tenants: List<TenantId>,
        retired: Set<TenantId> = emptySet(),
    ) {
        tenants.forEach { put(it, it.value.toString(), StructureNodeKind.TENANT, it in retired) }
        structure.onSnapshotComplete(structureVersion)
    }

    fun put(
        tenant: TenantId,
        ref: String,
        kind: StructureNodeKind,
        retired: Boolean = false,
    ) = structure.accept(
        OrganisationStructureChanged(
            tenant,
            ref,
            StructureChange.UPDATED,
            StructureNode(kind, null, retired, ref, null, emptyList(), ++structureVersion),
        ),
    )

    // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
    val consumerName = PackConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
    val consumer =
        JdbcInboxConsumer(
            ConsumerSubscription(consumerName, topic, PackConsumerConfiguration.SCHEMA),
            context.getBean(EventCatalogue::class.java),
            log,
            scope,
            dsl,
            context.getBean("packCurrencyChangedHandler", EventHandler::class.java),
            RebuildRequiredSink { },
            Clock.systemUTC(),
        )
}

const val SEQUENCE_MASK = 0x3fffffffffffL

const val WIDE = "00000000-0000-0000-0000-000000000000"

/** Shared fixtures of the pack consumer boundary tests (INV-AC-20913, INV-AC-22425). */
open class PackBoundarySupport {
    protected val actor = "svc:notification-svc"
    protected val at = Instant.parse("2026-11-12T10:00:00Z")
    protected val json = EnvelopeJson()
    protected val foreign = TenantId(UUID.randomUUID())
    protected var sequence = System.nanoTime() and SEQUENCE_MASK

    protected fun run(
        dir: Path,
        topic: String = PackConsumerConfiguration.TOPIC,
        block: Boundary.(TenantId) -> Unit,
    ) {
        NotificationContext.online(dir).run { context -> Boundary(context, topic).block(TenantId(UUID.randomUUID())) }
    }

    protected fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    protected fun policy(
        tenant: TenantId,
        recipients: String = "[\"u1\",\"u2\"]",
        tiers: String = "[{\"tier\":1,\"daysToDue\":30}]",
    ) = NotificationDb.superuser().use {
        it.createStatement().use { s ->
            s.execute(
                "insert into notification.alert_policy (tenant_id, entity_ref, alert_class, recipients, " +
                    "channels, escalation_tiers, updated_at) values ('${tenant.value}', '$WIDE', " +
                    "'PACK_STALENESS', '$recipients', '[]', '$tiers', now())",
            )
        }
    }

    /** A tenantless type is a platform event; any other type needs a real tenant, so the old type carries [foreign]. */
    protected fun changed(
        eventId: String,
        dep: String = "DEP-001",
        status: String = "RAISED",
        type: String = PackCurrencyChangedHandler.EVENT_TYPE,
        days: Int = 20,
    ): String {
        val platform = type == PackCurrencyChangedHandler.EVENT_TYPE
        return json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/rules-svc",
                type = type,
                subject = "pack",
                time = at,
                tenant = if (platform) TenantId.PLATFORM else foreign,
                entity = if (platform) EntityRef.PLATFORM else EntityRef("E-1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "rules-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = """{"depCode":"$dep","status":"$status","daysToEffectiveDate":$days}""",
            ),
        )
    }

    protected fun Boundary.alerts(tenant: TenantId): List<List<String?>> =
        tx(tenant) {
            dsl
                .selectFrom(ALERT)
                .fetch()
                .map { listOf(it.state, it.alertClass, it.trigger, it.entityRef) }
        }

    protected fun Boundary.inboxRows() =
        tx(TenantId.PLATFORM) { dsl.fetchCount(INBOX, INBOX.CONSUMER.eq(consumerName)) }

    protected fun raised(state: String = "RAISED") = listOf(state, "PACK_STALENESS", "PACK_NOT_ACTIVATED", WIDE)

    /** Read as the owner: a dead letter of a platform-tenant event is hidden from the runtime role (outbox-and-queue). */
    protected fun Boundary.deadLetters(): List<String?> =
        NotificationDb.superuser().use { c ->
            c.prepareStatement("select reason from notification.event_dead_letter where consumer = ?").use { st ->
                st.setString(1, consumerName)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
}

/**
 * INV-AC-20913 through the real inbox consumer (`JdbcInboxConsumer`) with the handler, the use case and the jOOQ
 * adapters of the TPL-18 context over PostgreSQL (P109). Only the event-log read is a list in memory: the `eventlog`
 * schema belongs to the event-log instance.
 */
class PackConsumerBoundaryTest : PackBoundarySupport() {
    @Test
    fun `INV-AC-20913 the first RAISED event stores one RAISED alert at the tenant-wide reference and is APPLIED`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e1"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe listOf(raised())
        inboxRows() shouldBe 1
    }

    @Test
    fun `INV-AC-20913 the repeated event id changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e1"))
        consumer.pollOnce().applied shouldBe 1
        log.add(changed("e1"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe listOf(raised())
        inboxRows() shouldBe 1
    }

    @Test
    fun `INV-AC-20913 a second RAISED id while the alert is open raises no second alert and is IGNORED`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e1"))
        log.add(changed("e2"))
        val result = consumer.pollOnce()
        result.applied shouldBe 1
        result.ignored shouldBe 1
        alerts(tenant) shouldBe listOf(raised())
    }

    @Test
    fun `INV-AC-20913 CLEARED sets the alert to RESOLVED and is APPLIED`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e1"))
        consumer.pollOnce().applied shouldBe 1
        log.add(changed("e3", status = "CLEARED"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe listOf(raised("RESOLVED"))
    }

    @Test
    fun `INV-AC-20913 DEP-002 is IGNORED and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e4", dep = "DEP-002"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20913 a RAISED event for a tenant with no policy raises no alert and is not marked processed`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        register(listOf(tenant))
        log.add(changed("e5"))
        // the platform consumer schedules a retry with backoff and rethrows; it dead-letters only after max attempts
        shouldThrow<PackAlertNotDelivered> { consumer.pollOnce() }
        alerts(tenant) shouldBe emptyList()
        inboxRows() shouldBe 0
        deadLetters() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20913 a payload with status RAISE goes to the dead letter at once`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e6", status = "RAISE"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        alerts(tenant) shouldBe emptyList()
        deadLetters() shouldBe listOf("status must be RAISED or CLEARED")
    }

    @Test
    fun `INV-AC-20913 a look-alike event type is refused to the dead letter`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e7", type = "com.dynokore.gst.platform.PackCurrencyChanged.v2"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        alerts(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20913 the whole sequence of the criterion in one consumer`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("e1"))
        log.add(changed("e1"))
        log.add(changed("e2"))
        log.add(changed("e3", status = "CLEARED"))
        log.add(changed("e4", dep = "DEP-002"))
        log.add(changed("e6", status = "RAISE"))
        consumer.pollOnce() shouldBe PollResult(applied = 2, ignored = 2, duplicates = 1, stale = 0, deadLettered = 1)
        alerts(tenant) shouldBe listOf(raised("RESOLVED"))
    }

    @Test
    fun `INV-AC-20913 the pack consumer bean of the context consumes the first event`(
        @TempDir dir: Path,
    ) {
        val log = Log()
        val tenant = TenantId(UUID.randomUUID())
        NotificationContext
            .online(dir)
            .withBean(EventLogReader::class.java, { log }, BeanDefinitionCustomizer { it.isPrimary = true })
            .run { context ->
                val boundary = Boundary(context, PackConsumerConfiguration.TOPIC)
                val bean = context.getBean("packConsumer", JdbcInboxConsumer::class.java)
                policy(tenant)
                boundary.register(listOf(tenant))
                log.add(changed("e1"))
                try {
                    bean.pollOnce() shouldBe
                        PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
                    boundary.alerts(tenant) shouldBe listOf(raised())
                } finally {
                    NotificationDb.superuser().use {
                        it.createStatement().use { s ->
                            s.execute(
                                "delete from notification.consumer_offset where consumer = " +
                                    "'${PackConsumerConfiguration.CONSUMER}' and topic = " +
                                    "'${PackConsumerConfiguration.TOPIC}'",
                            )
                        }
                    }
                }
            }
    }

    private fun backlogPolicy(tenant: TenantId) =
        NotificationDb.superuser().use {
            it.createStatement().use { s ->
                s.execute(
                    "insert into notification.alert_policy (tenant_id, entity_ref, alert_class, recipients, " +
                        "channels, updated_at, max_queue_depth) values ('${tenant.value}', 'E-1', 'BACKLOG', " +
                        "'[]', '[]', now(), 10)",
                )
            }
        }

    private fun backlogEvent(tenant: TenantId): String =
        json.write(
            Envelope(
                id = EventId("b1"),
                source = "/gst/submission-svc",
                type = "com.dynokore.gst.submission.BacklogValuesMeasured.v1",
                subject = "E-1",
                time = at,
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "submission-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"entityRef":"E-1","periodRef":"2026-Q1","measuredAt":"2026-11-12T09:59:00Z",""" +
                        """"queueDepth":11,"oldestUnsentAgeSeconds":5,"failureRate":0.1,"unacknowledgedCount":0}""",
            ),
        )

    /** Queues one replay item for notification-svc whose payload names [topic] and carries [raw]. */
    private fun Boundary.enqueueReplay(
        context: ApplicationContext,
        tenant: TenantId,
        topic: String,
        raw: String,
    ) {
        val queue =
            JdbcWorkQueue(
                scope,
                dsl,
                PackConsumerConfiguration.SCHEMA,
                context.getBean(LaneRateLimiter::class.java),
                Clock.systemUTC(),
                PackConsumerConfiguration.CONSUMER,
            )
        val payload = ObjectMapper().writeValueAsString(mapOf("deadLetterId" to 1, "topic" to topic, "raw" to raw))
        val entry = QueueEntry("p-${UUID.randomUUID()}", Lane.REPLAY, payload, PackConsumerConfiguration.CONSUMER)
        tx(tenant) { queue.enqueue(entry) }
    }

    /** The queue is shared by the tests: an item left released by one test would be claimed by another. */
    private fun Boundary.purgeReplayQueue(tenant: TenantId) =
        tx(tenant) { dsl.deleteFrom(DSL.table(DSL.name("notification", "work_queue"))).execute() }

    private fun Boundary.classes(tenant: TenantId) = alerts(tenant).map { it[1] }

    @Test
    fun `INV-AC-20913 a replayed pack letter reaches only the pack consumer`(
        @TempDir dir: Path,
    ) {
        NotificationContext.online(dir).run { context ->
            val b = Boundary(context, PackConsumerConfiguration.TOPIC)
            val tenant = TenantId(UUID.randomUUID())
            policy(tenant)
            b.register(listOf(tenant))
            backlogPolicy(tenant)
            b.enqueueReplay(context, TenantId.PLATFORM, PackConsumerConfiguration.TOPIC, changed("r1"))
            context.getBean(ReplayDelivery::class.java).runOnce() shouldBe ReplayOutcome.APPLIED
            b.alerts(tenant) shouldBe listOf(raised())
        }
    }

    @Test
    fun `INV-AC-20913 a replayed backlog letter reaches only the backlog consumer`(
        @TempDir dir: Path,
    ) {
        NotificationContext.online(dir).run { context ->
            val b = Boundary(context, PackConsumerConfiguration.TOPIC)
            val tenant = TenantId(UUID.randomUUID())
            policy(tenant)
            backlogPolicy(tenant)
            b.enqueueReplay(context, tenant, BacklogConsumerConfiguration.TOPIC, backlogEvent(tenant))
            context.getBean(ReplayDelivery::class.java).runOnce() shouldBe ReplayOutcome.APPLIED
            b.classes(tenant) shouldBe listOf("BACKLOG")
        }
    }

    @Test
    fun `INV-AC-20913 a replay item of a topic no consumer owns is refused and applied by neither consumer`(
        @TempDir dir: Path,
    ) {
        NotificationContext.online(dir).run { context ->
            val b = Boundary(context, PackConsumerConfiguration.TOPIC)
            val tenant = TenantId(UUID.randomUUID())
            policy(tenant)
            backlogPolicy(tenant)
            b.enqueueReplay(context, tenant, "gst.rules.rule-pack-activated.v1", changed("r2"))
            try {
                shouldThrow<UnroutableReplayItem> { context.getBean(ReplayDelivery::class.java).runOnce() }
                b.alerts(tenant) shouldBe emptyList()
            } finally {
                b.purgeReplayQueue(tenant)
            }
        }
    }

    @Test
    fun `INV-AC-20913 the only ReplayDelivery bean is notificationReplayDelivery`(
        @TempDir dir: Path,
    ) {
        NotificationContext.online(dir).run { context ->
            context.getBeansOfType(ReplayDelivery::class.java).keys shouldBe setOf("notificationReplayDelivery")
        }
    }
}

/** INV-AC-22425: the fan-out over the registered tenants through the real consumer. */
class PackFanOutBoundaryTest : PackBoundarySupport() {
    private fun Boundary.fanOutFixture(
        t1: TenantId,
        t2: TenantId,
        t4: TenantId,
    ) {
        policy(t1, recipients = "[\"u1\"]", tiers = "[{\"tier\":1,\"daysToDue\":30}]")
        policy(
            t2,
            recipients = "[\"u2\",\"u3\"]",
            tiers = "[{\"tier\":1,\"daysToDue\":30},{\"tier\":2,\"daysToDue\":10}]",
        )
        put(t1, "E-1", StructureNodeKind.ENTITY)
        put(TenantId.PLATFORM, TenantId.PLATFORM.value.toString(), StructureNodeKind.TENANT)
        register(listOf(t1, t2, t4), retired = setOf(t4))
    }

    private fun Boundary.tiers(tenant: TenantId): List<Int?> =
        tx(tenant) { dsl.select(ALERT.CURRENT_TIER).from(ALERT).fetch(0, Int::class.javaObjectType) }

    @Test
    fun `INV-AC-22425 one RAISED alert per registered tenant with its own tier, none for retired entity or platform`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (t1, t2, t4) = List(3) { TenantId(UUID.randomUUID()) }
        fanOutFixture(t1, t2, t4)
        log.add(changed("e1", days = 5))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe listOf(raised())
        alerts(t2) shouldBe listOf(raised())
        alerts(t4) shouldBe emptyList()
        alerts(TenantId.PLATFORM) shouldBe emptyList()
        tiers(t1) shouldBe listOf(1)
        tiers(t2) shouldBe listOf(2)
        inboxRows() shouldBe 1
    }

    @Test
    fun `INV-AC-22425 the repeated event id is a duplicate and a second RAISED id is IGNORED`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (t1, t2, t4) = List(3) { TenantId(UUID.randomUUID()) }
        fanOutFixture(t1, t2, t4)
        log.add(changed("e1"))
        consumer.pollOnce().applied shouldBe 1
        log.add(changed("e1"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        log.add(changed("e2"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe listOf(raised())
        alerts(t2) shouldBe listOf(raised())
    }

    @Test
    fun `INV-AC-22425 CLEARED sets both alerts to RESOLVED and DEP-002 is IGNORED`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (t1, t2, t4) = List(3) { TenantId(UUID.randomUUID()) }
        fanOutFixture(t1, t2, t4)
        log.add(changed("e1"))
        consumer.pollOnce().applied shouldBe 1
        log.add(changed("e3", status = "CLEARED"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe listOf(raised("RESOLVED"))
        alerts(t2) shouldBe listOf(raised("RESOLVED"))
        log.add(changed("e4", dep = "DEP-002"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe listOf(raised("RESOLVED"))
    }

    @Test
    fun `INV-AC-22425 a registered tenant with no policy is not delivered after the others were alerted`(
        @TempDir dir: Path,
    ) = run(dir) {
        val (t1, t2, t4) = List(3) { TenantId(UUID.randomUUID()) }
        val t3 = TenantId(UUID.randomUUID())
        fanOutFixture(t1, t2, t4)
        register(listOf(t1, t2, t3))
        log.add(changed("e5"))
        shouldThrow<PackAlertNotDelivered> { consumer.pollOnce() }
        alerts(t1) shouldBe listOf(raised())
        alerts(t2) shouldBe listOf(raised())
        alerts(t3) shouldBe emptyList()
        inboxRows() shouldBe 0
        deadLetters() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22425 a replica that is not synced raises no alert and the event is not delivered`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        put(tenant, tenant.value.toString(), StructureNodeKind.TENANT)
        log.add(changed("e6"))
        shouldThrow<PackAlertNotDelivered> { consumer.pollOnce() }
        alerts(tenant) shouldBe emptyList()
        inboxRows() shouldBe 0
        deadLetters() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22425 the old type, the look-alike type and the payload with status RAISE go to the dead letter`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant)
        register(listOf(tenant))
        log.add(changed("o1", type = "com.dynokore.gst.rules.PackCurrencyChanged.v1"))
        log.add(changed("o2", type = "com.dynokore.gst.platform.PackCurrencyChanged.v2"))
        log.add(changed("o3", status = "RAISE"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 3)
        alerts(tenant) shouldBe emptyList()
        deadLetters().size shouldBe 3
    }

    @Test
    fun `INV-AC-22425 the handler refuses an envelope of the platform type under a real tenant`(
        @TempDir dir: Path,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        // the envelope itself accepts a real tenant with the platform type, so the refusal is the handler's
        val envelope = json.read(changed("x1")).copy(tenant = tenant, entity = EntityRef("E-1"))
        NotificationContext.online(dir).run { context ->
            shouldThrow<ContractViolation> {
                context.getBean("packCurrencyChangedHandler", EventHandler::class.java).handle(envelope)
            }
        }
    }

    @Test
    fun `INV-AC-22425 the context requires the structure snapshot`(
        @TempDir dir: Path,
    ) {
        NotificationContext.online(dir).run { context ->
            context.environment.getProperty("serviceruntime.replica.structure.require-snapshot") shouldBe "true"
        }
    }
}
