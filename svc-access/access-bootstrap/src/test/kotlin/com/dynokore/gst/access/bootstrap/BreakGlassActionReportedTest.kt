package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The records a test puts on the topic; the consumer reads them through [EventLogReader] in offset order. */
class TopicFeed : EventLogReader {
    private val records = CopyOnWriteArrayList<LogRecord>()
    private val json = EnvelopeJson()

    fun put(envelope: Envelope) {
        records += LogRecord(NEXT_OFFSET.incrementAndGet(), json.write(envelope))
    }

    fun putRaw(envelopeJson: String) {
        records += LogRecord(NEXT_OFFSET.incrementAndGet(), envelopeJson)
    }

    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> = if (topic == TOPIC) records.filter { it.offset > afterOffset }.take(limit) else emptyList()

    companion object {
        const val TOPIC = "gst.platform.break-glass-action-reported.v1"

        /** The consumer's offset is stored in the shared test database, so offsets only grow across tests. */
        private val NEXT_OFFSET =
            java.util.concurrent.atomic
                .AtomicLong()
    }
}

/**
 * INV-AC-9168 (INV-Q-9117 A, INV-Q-9313 A): `BreakGlassActionReported` delivered through the inbox consumer of the real
 * context over a real PostgreSQL. The event log is [TopicFeed]; the rows, audit entries, outbox events and the counter
 * are read back and compared by equality.
 */
@Suppress("TooManyFunctions")
class BreakGlassActionReportedTest {
    private val type = "com.dynokore.gst.platform.BreakGlassActionReported.v1"
    private val auditType = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
    private val meter = "break_glass_report_rejected_total"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(BreakGlassController::class.java)
        val captured = context.getBean(CapturedEvents::class.java)
        val clock = context.getBean(java.time.Clock::class.java) as MovableClock
        val registry = context.getBean(MeterRegistry::class.java)
        val feed = context.getBean(TopicFeed::class.java)
        val consumer = context.getBean(InboxConsumer::class.java)
        val tenant = TenantId(UUID.randomUUID())
        private var seq = 0

        /** Seeds the two people and unlocks a session for [forTenant]; returns its unlock reference. */
        fun unlock(forTenant: TenantId = tenant): String {
            Seeds.grant(context, forTenant, SeedSpec("IDN-ADM", Role.TENANT_ADMIN))
            Seeds.grant(context, forTenant, SeedSpec("IDN-APP", Role.TAX_APPROVER))
            BreakGlassApproving.approve(context, forTenant, "IDN-APP")
            return tenants
                .bind(forTenant, "IDN-ADM") {
                    api.unlockBreakGlass(
                        UUID.randomUUID(),
                        UnlockBreakGlassRequest(forTenant.toString(), listOf("IDN-ADM", "IDN-APP")),
                    )
                }.body!!
                .unlockRef
        }

        fun expiresAt(
            unlockRef: String,
            forTenant: TenantId = tenant,
        ): Instant =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select expires_at from access.break_glass_session where tenant_id = ? and unlock_ref = ?",
                    ).use { s ->
                        s.setObject(1, forTenant.value)
                        s.setString(2, unlockRef)
                        s.executeQuery().use {
                            check(it.next())
                            it.getTimestamp(1).toInstant()
                        }
                    }
            }

        fun payload(
            reportId: String,
            unlockRef: String,
            action: String = "VIEW",
            occurredAt: Instant = clock.instant(),
            extra: String = "",
        ) = """{"reportId":"$reportId","unlockRef":"$unlockRef","action":"$action",""" +
            """"occurredAt":"$occurredAt"$extra}"""

        fun envelope(
            data: String,
            source: String = "/gst/tenancy-svc",
            eventType: String = type,
            forTenant: TenantId = tenant,
            id: String = UUID.randomUUID().toString(),
        ) = Envelope(
            id = EventId(id),
            source = source,
            type = eventType,
            subject = "break-glass",
            time = clock.instant(),
            tenant = forTenant,
            entity =
                if (Envelope.allowsTenantWide(
                        eventType,
                    )
                ) {
                    EntityRef.TENANT_WIDE
                } else {
                    EntityRef(UUID.randomUUID().toString())
                },
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("c-${++seq}"),
            causation = CausationId("c-$seq"),
            dataJson = data,
        )

        /** Polls until the consumer has read everything on the topic; the sum of what it did. */
        fun deliver(): PollResult {
            var applied = 0
            var ignored = 0
            var duplicates = 0
            var stale = 0
            var dead = 0
            while (true) {
                val result = consumer.pollOnce()
                if (result.total == 0) break
                applied += result.applied
                ignored += result.ignored
                duplicates += result.duplicates
                stale += result.stale
                dead += result.deadLettered
            }
            return PollResult(applied, ignored, duplicates, stale, dead)
        }

        fun alerts() = captured.envelopes.filter { it.type == BreakGlassAlertOutbox.EVENT_TYPE }

        fun audits() =
            captured.envelopes.filter { it.type == auditType && it.dataJson.contains("\"recordBreakGlassAction\"") }

        fun outbox() = captured.envelopes.size

        /** The `(report_ref, caller_service, action)` rows of [forTenant], ordered by report reference. */
        fun rows(forTenant: TenantId = tenant): List<Triple<String?, String?, String>> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select report_ref, caller_service, action from access.break_glass_action " +
                            "where tenant_id = ? order by report_ref nulls first",
                    ).use { s ->
                        s.setObject(1, forTenant.value)
                        s.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        Triple(rs.getString(1), rs.getString(2), rs.getString(3)),
                                    )
                                }
                            }
                        }
                    }
            }

        fun counter(reason: String): Double =
            registry
                .find(meter)
                .tag("reason", reason)
                .counter()
                ?.count() ?: 0.0

        /** Nothing stored, raised or written since [mark]. */
        fun assertNothingSince(mark: Int) {
            assertThat(rows()).isEmpty()
            assertThat(outbox()).isEqualTo(mark)
        }
    }

    private fun run(block: (Env) -> Unit) {
        val topic = TopicFeed()
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { MovableDirectory() })
            // registered before the configuration classes are read: the feed's @ConditionalOnBean must see them
            .withInitializer { ctx ->
                (ctx as org.springframework.context.support.GenericApplicationContext).apply {
                    registerBean(TopicFeed::class.java, java.util.function.Supplier { topic })
                    registerBean(
                        RebuildRequiredSink::class.java,
                        java.util.function.Supplier { RebuildRequiredSink { } },
                    )
                }
            }.run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    @Test
    fun `INV-AC-9168 the context starts without the event-log beans, with the use case and the handler only`() {
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { MovableDirectory() })
            .run { context ->
                context.startupFailure?.let { throw it }
                // no RebuildRequiredSink bean: the consumer is not built, everything else is
                assertThat(context.getBeanNamesForType(InboxConsumer::class.java)).isEmpty()
                assertThat(
                    context.getBeansOfType(
                        com.dynokore.gst.access.application.breakglass.ReportBreakGlassActionUseCase::class.java,
                    ),
                ).hasSize(1)
                assertThat(
                    context.getBeansOfType(
                        com.dynokore.gst.access.adapters.events.BreakGlassActionReportedHandler::class.java,
                    ),
                ).hasSize(1)
                assertThat(
                    context.getBeansOfType(
                        com.dynokore.gst.access.application.breakglass.BreakGlassReportObserver::class.java,
                    ),
                ).hasSize(1)
            }
    }

    @Test
    fun `INV-AC-9168 an event from tenancy-svc stores one row, one alert, one audit entry and a repeat nothing more`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            val event = env.envelope(env.payload("R1", ref))
            env.feed.put(event)
            val first = env.deliver()
            assertThat(first.applied).isEqualTo(1)
            assertThat(env.rows()).isEqualTo(listOf(Triple("R1", "tenancy-svc", "VIEW")))
            assertThat(env.alerts().size).isEqualTo(1)
            assertThat(env.alerts().single().dataJson).contains("\"accCode\":\"ACC-002\"", "\"action\":\"VIEW\"", ref)
            assertThat(env.audits().size).isEqualTo(1)
            val after = env.outbox()
            // the second delivery of the same event
            env.feed.put(event)
            val second = env.deliver()
            assertThat(second.applied).isEqualTo(0)
            assertThat(env.rows().size).isEqualTo(1)
            assertThat(env.alerts().size).isEqualTo(1)
            assertThat(env.audits().size).isEqualTo(1)
            assertThat(env.outbox()).isEqualTo(after)
            // the three refusals are counted, acknowledged and store nothing
            env.feed.put(env.envelope(env.payload("R2", ref, occurredAt = env.expiresAt(ref))))
            env.feed.put(env.envelope(env.payload("R3", "BG-NONE")))
            env.feed.put(env.envelope(env.payload("R4", ref, action = "SHUTDOWN")))
            val refused = env.deliver()
            assertThat(refused.ignored).isEqualTo(3)
            assertThat(refused.deadLettered).isEqualTo(0)
            assertThat(env.rows().size).isEqualTo(1)
            assertThat(env.outbox()).isEqualTo(after)
            assertThat(
                listOf("NOT_OPEN", "NOT_FOUND", "BAD_ACTION").map { env.counter(it) },
            ).isEqualTo(listOf(1.0, 1.0, 1.0))
        }

    @Test
    fun `INV-AC-9168 a source that the access contract does not declare is refused with nothing written`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            env.feed.put(env.envelope(env.payload("R1", ref), source = "/gst/transport-svc"))
            env.feed.put(env.envelope(env.payload("R2", ref), source = "/gst/tenancy-svc-alt"))
            val result = env.deliver()
            assertThat(result.deadLettered).isEqualTo(2)
            env.assertNothingSince(0)
        }

    @Test
    fun `INV-AC-9168 the same reportId for tenant T and tenant T2 stores one row for each`() =
        run { env ->
            val t2 = TenantId(UUID.randomUUID())
            val ref1 = env.unlock()
            val ref2 = env.unlock(t2)
            env.feed.put(env.envelope(env.payload("R1", ref1)))
            env.feed.put(env.envelope(env.payload("R1", ref2), forTenant = t2))
            assertThat(env.deliver().applied).isEqualTo(2)
            assertThat(env.rows()).isEqualTo(listOf(Triple("R1", "tenancy-svc", "VIEW")))
            assertThat(env.rows(t2)).isEqualTo(listOf(Triple("R1", "tenancy-svc", "VIEW")))
        }

    @Test
    fun `INV-AC-9168 the same reportId as two events with different event ids stores one row, one alert, one audit`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            env.feed.put(env.envelope(env.payload("R1", ref)))
            env.feed.put(env.envelope(env.payload("R1", ref)))
            val result = env.deliver()
            assertThat(listOf(result.applied, result.ignored)).isEqualTo(listOf(1, 1))
            assertThat(env.rows().size).isEqualTo(1)
            assertThat(env.alerts().size).isEqualTo(1)
            assertThat(env.audits().size).isEqualTo(1)
        }

    @Test
    fun `INV-AC-9168 an unlock reference that only looks like the session is not found and is counted`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            env.feed.put(env.envelope(env.payload("R1", ref + "0")))
            assertThat(env.deliver().ignored).isEqualTo(1)
            env.assertNothingSince(0)
            assertThat(env.counter("NOT_FOUND")).isEqualTo(1.0)
            assertThat(env.counter("NOT_OPEN")).isEqualTo(0.0)
        }

    @Test
    fun `INV-AC-9168 occurredAt one second before expiresAt is stored and at expiresAt is rejected as NOT_OPEN`() =
        run { env ->
            val ref = env.unlock()
            val expires = env.expiresAt(ref)
            env.feed.put(env.envelope(env.payload("R1", ref, occurredAt = expires.minusSeconds(1))))
            env.feed.put(env.envelope(env.payload("R2", ref, occurredAt = expires)))
            val result = env.deliver()
            assertThat(listOf(result.applied, result.ignored)).isEqualTo(listOf(1, 1))
            assertThat(env.rows().map { it.first }).isEqualTo(listOf("R1"))
            assertThat(env.counter("NOT_OPEN")).isEqualTo(1.0)
        }

    @Test
    fun `INV-AC-9168 a payload callerService other-svc is ignored and tenancy-svc is stored`() =
        run { env ->
            val ref = env.unlock()
            env.feed.put(env.envelope(env.payload("R1", ref, extra = ""","callerService":"other-svc"""")))
            assertThat(env.deliver().applied).isEqualTo(1)
            assertThat(env.rows()).isEqualTo(listOf(Triple("R1", "tenancy-svc", "VIEW")))
        }

    @Test
    fun `INV-AC-9168 an action SHUTDOWN stores nothing, is counted as BAD_ACTION, logged and acknowledged`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            val logger =
                org.slf4j.LoggerFactory.getLogger(BreakGlassReportFeed::class.java) as ch.qos.logback.classic.Logger
            val lines =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            lines.start()
            logger.addAppender(lines)
            try {
                env.feed.put(
                    env.envelope(env.payload("R1", ref, action = "SHUTDOWN", extra = ""","target":"secret-target"""")),
                )
                val result = env.deliver()
                assertThat(listOf(result.ignored, result.deadLettered)).isEqualTo(listOf(1, 0))
            } finally {
                logger.detachAppender(lines)
            }
            env.assertNothingSince(0)
            assertThat(env.counter("BAD_ACTION")).isEqualTo(1.0)
            assertThat(lines.list.map { it.level.toString() to it.formattedMessage })
                .isEqualTo(listOf("WARN" to "break-glass report R1 for unlock $ref refused: BAD_ACTION"))
            // a second poll finds nothing: the offset moved past it
            assertThat(env.consumer.pollOnce().total).isEqualTo(0)
        }

    @Test
    fun `INV-AC-9168 a payload without reportId is a poison message and stores nothing`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            env.feed.put(
                env.envelope("""{"unlockRef":"$ref","action":"VIEW","occurredAt":"${env.clock.instant()}"}"""),
            )
            assertThat(env.deliver().deadLettered).isEqualTo(1)
            env.assertNothingSince(0)
        }

    @Test
    fun `INV-AC-9168 the look-alike types of the handler store nothing`() =
        run { env ->
            val ref = env.unlock()
            env.captured.envelopes.clear()
            val data = env.payload("R1", ref)
            listOf(
                "com.dynokore.gst.platform.BreakGlassActionReportedX.v1",
                "com.dynokore.gst.platform.BreakGlassActionReported.v2",
                "com.dynokore.gst.platform.BreakGlassAlertRaised.v1",
            ).forEach { env.feed.put(env.envelope(data, eventType = it)) }
            env.deliver()
            env.assertNothingSince(0)
        }

    private fun insert(
        tenant: TenantId,
        unlockRef: String,
        reportRef: String,
    ): Int =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "insert into access.break_glass_action (id, tenant_id, action_ref, unlock_ref, action, " +
                        "created_at, created_by, updated_at, updated_by, version, report_ref) " +
                        "values (?, ?, ?, ?, 'VIEW', now(), 'direct', now(), 'direct', 0, ?)",
                ).use { s ->
                    s.setObject(1, UUID.randomUUID())
                    s.setObject(2, tenant.value)
                    s.setString(3, "BGA-" + UUID.randomUUID().toString().take(8))
                    s.setString(4, unlockRef)
                    s.setString(5, reportRef)
                    s.executeUpdate()
                }
        }

    @Test
    fun `INV-AC-9168 the unique constraint refuses a repeated tenant and report_ref and accepts another tenant`() =
        run { env ->
            val t2 = TenantId(UUID.randomUUID())
            val ref1 = env.unlock()
            val ref2 = env.unlock(t2)
            assertThat(insert(env.tenant, ref1, "R9")).isEqualTo(1)
            val refused = catchThrowable { insert(env.tenant, ref1, "R9") }
            assertThat(refused).isInstanceOf(SQLException::class.java)
            // 23505 unique_violation
            assertThat((refused as SQLException).sqlState).isEqualTo("23505")
            assertThat(insert(t2, ref2, "R9")).isEqualTo(1)
        }

    @Test
    fun `INV-AC-9168 a row of the synchronous path keeps a null report_ref`() =
        run { env ->
            val ref = env.unlock()
            (env.context.getBean(ServiceCaller::class.java) as MovableServiceCaller).value = "ingestion-svc"
            env.tenants.bind(env.tenant, "IDN-ADM") {
                env.api.recordBreakGlassAction(
                    ref,
                    UUID.randomUUID(),
                    BreakGlassActionRequest(BreakGlassActionRequest.Action.VIEW),
                )
            }
            assertThat(env.rows().map { it.first }).isEqualTo(listOf<String?>(null))
        }
}
