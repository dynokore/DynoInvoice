package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.application.metering.LimitKind
import com.dynokore.gst.tenancy.application.metering.PlanLimitPublisher
import com.dynokore.gst.tenancy.contract.model.RecordEntitlementRequest
import com.dynokore.gst.tenancy.contract.model.UsageCountersResponse
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * Metering, archive volume and plan limits through the real application context over PostgreSQL (INV-REQ-878,
 * INV-REQ-879): recorded envelope fixtures are read by the inbox consumers exactly as the event log delivers them.
 */
private const val OCT_FIRST = "2026-10-01T00:00:00Z"

@Suppress("LargeClass") // one test per probe of the metering done-when; the helpers are shared
class MeteringTest {
    private val json = EnvelopeJson()

    private class Log : EventLogReader {
        private val records = mutableMapOf<String, MutableList<LogRecord>>()

        fun add(
            topic: String,
            envelopeJson: String,
        ) {
            records.getOrPut(topic) { mutableListOf() } += LogRecord(OFFSETS.incrementAndGet(), envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records[topic].orEmpty().filter { it.offset > afterOffset }.take(limit)

        private companion object {
            val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000 + 500)
        }
    }

    private inner class Env(
        val env: ProfileEnv,
        val log: Log,
        val raised: Raised,
    ) {
        val metering = env.context.getBean(com.dynokore.gst.tenancy.application.metering.Metering::class.java)

        fun poll() {
            listOf(
                "submissionAcknowledgedConsumer",
                "archiveVolumeMeasuredConsumer",
                "tenantWideArchiveVolumeMeasuredConsumer",
            ).forEach { env.context.getBean(it, InboxConsumer::class.java).pollOnce() }
        }

        /** A tenant with the two entities E1 and E2 in one group; [block] runs bound to it. */
        fun <T> tenant(block: (Tenant) -> T): T {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            env.tenantsApi.createTenant(
                UUID.randomUUID(),
                com.dynokore.gst.tenancy.contract.model
                    .CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref),
            )
            val id = TenantRefs.idOf(ref)
            return env.inTenant(id) {
                val group = env.group(id)
                block(Tenant(ref, id, env.entity(id, group).entityRef, env.entity(id, group).entityRef, group))
            }
        }

        fun usage(
            tenant: Tenant,
            period: String,
            entityRef: String? = null,
        ): UsageCountersResponse =
            env.inTenant(tenant.id) { env.tenantsApi.getUsageCounters(tenant.ref, period, entityRef).body!! }

        fun entityDocs(
            tenant: Tenant,
            period: String,
            entity: String,
        ) = usage(tenant, period).entities!!.firstOrNull { it.entityRef == entity }
    }

    private data class Tenant(
        val ref: String,
        val id: TenantId,
        val e1: String,
        val e2: String,
        val group: com.dynokore.gst.tenancy.contract.model.GroupResponse,
    )

    /** The recorded raises: the outbox binding of PlanLimitExceeded waits for a ruling on its entity reference. */
    private class Raised : PlanLimitPublisher {
        data class Event(
            val kind: LimitKind,
            val period: String?,
            val limit: Int,
            val observed: Int,
        )

        val events = mutableListOf<Event>()

        override fun exceeded(
            kind: LimitKind,
            period: String?,
            limit: Int,
            observed: Int,
        ) {
            events += Event(kind, period, limit, observed)
        }
    }

    private fun run(block: (Env) -> Unit) {
        val log = Log()
        val raised = Raised()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .withBean(PlanLimitPublisher::class.java, { raised })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(ProfileEnv(context), log, raised))
            }
    }

    @Suppress("LongParameterList") // mirrors the event fields the tests vary
    private fun envelope(
        tenant: TenantId,
        type: String,
        entity: String,
        data: String,
        id: String = "ev-${UUID.randomUUID()}",
        testMode: Boolean = false,
    ): String =
        json.write(
            Envelope(
                id = EventId(id),
                source = "/gst/test",
                type = type,
                subject = "s-1",
                time = Instant.parse("2026-10-27T10:00:00Z"),
                tenant = tenant,
                entity = EventEntityRef(entity),
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = data,
                testMode = testMode,
            ),
        )

    @Suppress("LongParameterList") // mirrors the event fields the tests vary
    private fun ack(
        t: Tenant,
        entity: String,
        timestamp: String,
        version: Int = 1,
        id: String = "ev-${UUID.randomUUID()}",
        testMode: Boolean = false,
        tenant: TenantId = t.id,
    ) = envelope(
        tenant,
        TenancyEventCatalogue.SUBMISSION_ACKNOWLEDGED_TYPE,
        entity,
        """{"docUuid":"${UUID.randomUUID()}","version":$version,"acknowledgementId":"a-1","timestamp":"$timestamp"}""",
        id,
        testMode,
    )

    private fun Env.ack(text: String) = log.add(TenancyEventCatalogue.SUBMISSION_ACKNOWLEDGED_TOPIC, text)

    @Suppress("LongParameterList") // mirrors the event fields the tests vary
    private fun volume(
        t: Tenant,
        entity: String,
        bytes: Long,
        at: String,
        tenantRef: String = t.ref,
        id: String = "ev-${UUID.randomUUID()}",
        payloadEntity: String = entity,
    ) = envelope(
        t.id,
        TenancyEventCatalogue.ARCHIVE_VOLUME_TYPE,
        entity,
        """{"tenantRef":"$tenantRef","entityRef":"$payloadEntity","bytes":$bytes,"measuredAt":"$at"}""",
        id,
    )

    @Suppress("LongParameterList") // mirrors the event fields the tests vary
    private fun wide(
        t: Tenant,
        bytes: Long,
        at: String,
        tenantRef: String = t.ref,
        type: String = TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TYPE,
        id: String = "ev-${UUID.randomUUID()}",
    ) = envelope(t.id, type, "TENANT_WIDE", """{"tenantRef":"$tenantRef","bytes":$bytes,"measuredAt":"$at"}""", id)

    @Test
    fun `INV-AC-9587 counts are kept apart per entity and period and a test-mode event is not counted`() {
        run { env ->
            env.tenant { t ->
                val e3 = "NO-EVENTS"
                repeat(3) { env.ack(ack(t, t.e1, "2026-10-05T10:00:00Z")) }
                env.ack(ack(t, t.e1, "2026-10-06T10:00:00Z", version = 2))
                env.ack(ack(t, t.e1, "2026-11-02T10:00:00Z"))
                repeat(2) { env.ack(ack(t, t.e2, "2026-10-07T10:00:00Z")) }
                env.poll()
                val before = env.entityDocs(t, "2026-10", t.e1)!!
                env.ack(ack(t, t.e1, "2026-10-08T10:00:00Z", testMode = true))
                env.poll()
                val after = env.entityDocs(t, "2026-10", t.e1)!!
                assertThat(before.documentsThisPeriod).isEqualTo(3)
                assertThat(before.resubmissions).isEqualTo(1)
                assertThat(after).isEqualTo(before)
                assertThat(env.entityDocs(t, "2026-11", t.e1)!!.documentsThisPeriod).isEqualTo(1)
                assertThat(env.entityDocs(t, "2026-10", t.e2)!!.documentsThisPeriod).isEqualTo(2)
                assertThat(env.entityDocs(t, "2026-10", e3)).isNull()
                assertThat(env.usage(t, "2026-10", e3).entities).isEmpty()
                val month = env.usage(t, "2026-10")
                assertThat(month.documentsThisPeriod).isEqualTo(5)
                assertThat(month.resubmissions).isEqualTo(1)
                assertThat(month.period).isEqualTo("2026-10")
                assertThat(env.usage(t, "2026-10", t.e2).entities!!.map { it.entityRef }).containsExactly(t.e2)
            }
        }
    }

    @Test
    fun `INV-AC-9587 the same envelope id delivered twice counts once`() {
        run { env ->
            env.tenant { t ->
                val text = ack(t, t.e1, "2026-10-05T10:00:00Z", id = "ev-dup-${UUID.randomUUID()}")
                env.ack(text)
                env.poll()
                env.ack(text)
                env.poll()
                assertThat(env.usage(t, "2026-10").documentsThisPeriod).isEqualTo(1)
            }
        }
    }

    @Test
    fun `INV-AC-9587 testmode true counts nothing and testmode false and a missing member count once each`() {
        run { env ->
            env.tenant { t ->
                val plain = ack(t, t.e1, "2026-10-05T10:00:00Z")
                assertThat(plain).doesNotContain("testmode")
                env.ack("{\"testmode\":true," + plain.removePrefix("{"))
                env.poll()
                assertThat(env.usage(t, "2026-10").documentsThisPeriod).isZero()
                env.ack("{\"testmode\":false," + ack(t, t.e1, "2026-10-05T10:00:00Z").removePrefix("{"))
                env.ack(ack(t, t.e1, "2026-10-05T10:00:00Z"))
                env.poll()
                assertThat(env.usage(t, "2026-10").documentsThisPeriod).isEqualTo(2)
            }
        }
    }

    @Test
    fun `INV-AC-9587 version 1 and version 2 of one document give one document and one resubmission`() {
        run { env ->
            env.tenant { t ->
                env.ack(ack(t, t.e1, "2026-10-05T10:00:00Z", version = 1))
                env.ack(ack(t, t.e1, "2026-10-05T11:00:00Z", version = 2))
                env.poll()
                val item = env.entityDocs(t, "2026-10", t.e1)!!
                assertThat(item.documentsThisPeriod).isEqualTo(1)
                assertThat(item.resubmissions).isEqualTo(1)
            }
        }
    }

    @Test
    fun `INV-AC-9587 the UTC month of the payload timestamp decides the period at the boundary`() {
        run { env ->
            env.tenant { t ->
                env.ack(ack(t, t.e1, "2026-10-31T23:59:59Z"))
                env.ack(ack(t, t.e1, "2026-11-01T00:00:00Z"))
                env.poll()
                assertThat(env.entityDocs(t, "2026-10", t.e1)!!.documentsThisPeriod).isEqualTo(1)
                assertThat(env.entityDocs(t, "2026-11", t.e1)!!.documentsThisPeriod).isEqualTo(1)
            }
        }
    }

    @Test
    fun `INV-AC-9587 an event for an entity of another tenant changes no counter of this tenant`() {
        run { env ->
            env.tenant { a ->
                env.tenant { b ->
                    env.ack(ack(a, b.e1, "2026-10-05T10:00:00Z"))
                    env.poll()
                    assertThat(env.usage(a, "2026-10").documentsThisPeriod).isZero()
                    assertThat(env.usage(b, "2026-10").documentsThisPeriod).isZero()
                }
            }
        }
    }

    @Test
    fun `INV-AC-9587 a deregistered entity is not in activeEntities`() {
        run { env ->
            env.tenant { t ->
                assertThat(env.usage(t, "2026-10").activeEntities).isEqualTo(2)
                BoundaryFixture.asSuperuser { c ->
                    c.prepareStatement("update tenancy.entity set lifecycle_state = 'DEREGISTERED' where ref = ?").use {
                        it.setString(1, t.e2)
                        it.executeUpdate()
                    }
                }
                assertThat(env.usage(t, "2026-10").activeEntities).isEqualTo(1)
            }
        }
    }

    @Test
    fun `INV-AC-1906 archive volume sums the latest entity values and a later measuredAt replaces`() {
        run { env ->
            env.tenant { t ->
                val none = env.usage(t, "2026-10")
                assertThat(none.archiveVolumeBytes).isNull()
                val id = "ev-vol-${UUID.randomUUID()}"
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e1, 100, OCT_FIRST, id = id))
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e1, 100, OCT_FIRST, id = id))
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e2, 200, "2026-10-01T00:00:00Z"))
                env.poll()
                assertThat(env.usage(t, "2026-10").archiveVolumeBytes).isEqualTo(300)
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e1, 150, "2026-10-02T00:00:00Z"))
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e2, 999, "2026-09-30T00:00:00Z"))
                env.poll()
                val usage = env.usage(t, "2026-10")
                assertThat(usage.archiveVolumeBytes).isEqualTo(350)
                val volumes = usage.entities!!.associate { it.entityRef to it.archiveVolumeBytes }
                assertThat(volumes)
                    .containsEntry(t.e1, 150L)
                    .containsEntry(t.e2, 200L)
            }
        }
    }

    @Test
    fun `INV-AC-1906 an archive volume of bytes 0 gives 0 and a tenantRef of another tenant stores nothing`() {
        run { env ->
            env.tenant { t ->
                env.tenant { other ->
                    val foreign = volume(t, t.e1, 500, OCT_FIRST, tenantRef = other.ref)
                    env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, foreign)
                    env.poll()
                    assertThat(env.usage(t, "2026-10").archiveVolumeBytes).isNull()
                    env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e1, 0, "2026-10-02T00:00:00Z"))
                    env.poll()
                    assertThat(env.usage(t, "2026-10").archiveVolumeBytes).isEqualTo(0)
                    assertThat(env.usage(other, "2026-10").archiveVolumeBytes).isNull()
                }
            }
        }
    }

    @Test
    fun `INV-AC-16558 the tenant volume adds the tenant-wide value to the entity values and creates no entity`() {
        run { env ->
            env.tenant { t ->
                val topic = TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TOPIC
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e1, 100, "2026-10-01T00:00:00Z"))
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e2, 200, "2026-10-01T00:00:00Z"))
                val id = "ev-wide-${UUID.randomUUID()}"
                env.log.add(topic, wide(t, 500, "2026-10-01T00:00:00Z", id = id))
                env.log.add(topic, wide(t, 500, "2026-10-01T00:00:00Z", id = id))
                env.poll()
                val usage = env.usage(t, "2026-10")
                assertThat(usage.archiveVolumeBytes).isEqualTo(800)
                assertThat(usage.entities!!.map { it.entityRef }).containsExactlyInAnyOrder(t.e1, t.e2)
                env.log.add(topic, wide(t, 700, "2026-10-03T00:00:00Z"))
                env.log.add(topic, wide(t, 1, "2026-10-02T00:00:00Z"))
                env.poll()
                assertThat(env.usage(t, "2026-10").archiveVolumeBytes).isEqualTo(1000)
            }
        }
    }

    @Test
    fun `INV-AC-16558 a tenant with only a tenant-wide event has its value and no entities`() {
        run { env ->
            env.tenant { t ->
                env.log.add(TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TOPIC, wide(t, 42, "2026-10-01T00:00:00Z"))
                env.poll()
                val usage = env.usage(t, "2026-10")
                assertThat(usage.archiveVolumeBytes).isEqualTo(42)
                assertThat(usage.entities).isEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-16558 a tenant-wide tenantRef of another tenant and the look-alike type store nothing`() {
        run { env ->
            env.tenant { t ->
                env.tenant { other ->
                    val topic = TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TOPIC
                    env.log.add(topic, wide(t, 5, OCT_FIRST, tenantRef = other.ref))
                    env.log.add(
                        topic,
                        wide(t, 6, OCT_FIRST, type = "com.dynokore.gst.evidence.TenantWideArchiveVolumeMeasured.v1"),
                    )
                    env.poll()
                    assertThat(env.usage(t, "2026-10").archiveVolumeBytes).isNull()
                    assertThat(env.usage(other, "2026-10").archiveVolumeBytes).isNull()
                }
            }
        }
    }

    @Test
    fun `INV-AC-16558 the tenant-wide row is stored under the reserved entity value and not the empty string`() {
        run { env ->
            env.tenant { t ->
                env.log.add(TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TOPIC, wide(t, 9, OCT_FIRST))
                env.poll()
                assertThat(storedEntityRefs(t)).containsExactly(EventEntityRef.TENANT_WIDE.value)
                assertThat(storedEntityRefs(t)).doesNotContain("")
                assertThat(env.usage(t, "2026-10").entities).isEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-16558 a per-entity event carrying the reserved value stores nothing and lists no entity`() {
        run { env ->
            env.tenant { t ->
                val reserved = EventEntityRef.TENANT_WIDE.value
                env.log.add(
                    TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC,
                    volume(t, t.e2, 77, OCT_FIRST, payloadEntity = reserved),
                )
                env.log.add(TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC, volume(t, t.e1, 5, OCT_FIRST))
                env.poll()
                assertThat(storedEntityRefs(t)).containsExactly(t.e1)
                assertThat(env.usage(t, "2026-10").archiveVolumeBytes).isEqualTo(5)
                assertThat(env.usage(t, "2026-10").entities!!.map { it.entityRef }).containsExactly(t.e1)
            }
        }
    }

    @Test
    fun `INV-AC-16558 the CHECK of archive_volume entity_ref admits no zero-length value`() {
        val definition =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select pg_get_constraintdef(k.oid) from pg_constraint k " +
                            "where k.conrelid = 'tenancy.archive_volume'::regclass and k.contype = 'c' " +
                            "and pg_get_constraintdef(k.oid) like '%entity_ref%'",
                    ).use { s ->
                        s.executeQuery().use { rows ->
                            val all = generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                            all
                        }
                    }
            }
        assertThat(definition).hasSize(1)
        assertThat(definition.single()).isEqualTo("CHECK (((length(entity_ref) >= 1) AND (length(entity_ref) <= 64)))")
    }

    private fun storedEntityRefs(t: Tenant): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select entity_ref from tenancy.archive_volume where tenant_id = ?").use { s ->
                s.setObject(1, t.id.value)
                s.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    private fun Env.limit(
        t: Tenant,
        documents: Int,
        entities: Int = 100,
    ) = env.inTenant(t.id) {
        env.tenantsApi.recordEntitlement(UUID.randomUUID(), t.ref, RecordEntitlementRequest("P1", entities, documents))
    }

    private fun Env.exceeded() = raised.events.toList()

    @Test
    fun `INV-AC-9588 the first excess publishes one event, a second in the period none, the next period again`() {
        run { env ->
            env.tenant { t ->
                env.limit(t, documents = 2)
                repeat(2) { env.ack(ack(t, t.e1, "2026-10-05T10:00:00Z")) }
                env.poll()
                assertThat(env.exceeded()).isEmpty()
                env.ack(ack(t, t.e1, "2026-10-06T10:00:00Z"))
                env.poll()
                assertThat(env.exceeded()).hasSize(1)
                val event = Raised.Event(LimitKind.DOCUMENTS_PER_MONTH, "2026-10", 2, 3)
                assertThat(env.exceeded().single()).isEqualTo(event)
                env.ack(ack(t, t.e1, "2026-10-07T10:00:00Z"))
                env.poll()
                assertThat(env.exceeded()).hasSize(1)
                repeat(3) { env.ack(ack(t, t.e1, "2026-11-07T10:00:00Z")) }
                env.poll()
                assertThat(env.exceeded()).hasSize(2)
                assertThat(env.usage(t, "2026-10").documentsThisPeriod).isEqualTo(4)
            }
        }
    }

    @Test
    fun `INV-AC-9588 no stored limit publishes nothing and a tenant over its limit still reads its usage`() {
        run { env ->
            env.tenant { t ->
                val now = Instant.now()
                val month =
                    com.dynokore.gst.tenancy.application.metering.Metering
                        .periodOf(now)
                repeat(5) { env.ack(ack(t, t.e1, now.toString())) }
                env.poll()
                assertThat(env.exceeded()).isEmpty()
                env.limit(t, documents = 1)
                assertThat(env.exceeded()).hasSize(1)
                val response = env.env.inTenant(t.id) { env.env.tenantsApi.getUsageCounters(t.ref, month, null) }
                assertThat(response.statusCode.value()).isEqualTo(200)
                assertThat(response.body!!.documentsThisPeriod).isEqualTo(5)
            }
        }
    }

    @Test
    fun `INV-AC-9588 the entities limit publishes ENTITIES without a period`() {
        run { env ->
            env.tenant { t ->
                env.limit(t, documents = 100, entities = 1)
                assertThat(env.exceeded().single()).isEqualTo(Raised.Event(LimitKind.ENTITIES, null, 1, 2))
            }
        }
    }
}
