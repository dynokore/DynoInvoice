package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.certification.CertifyOutcome
import com.dynokore.gst.tenancy.application.certification.CertifySandbox
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal const val RECORD_KEY = "sandbox-certification.record"
internal const val STATUS_CHANGED = "com.dynokore.gst.tenancy.CertificationStatusChanged.v1"
internal const val STATUS_TOPIC = "gst.tenancy.certification-status-changed.v1"
internal const val PACK_TOPIC = "gst.rules.rule-pack-activated.v1"
internal const val PACK_TYPE = "com.dynokore.gst.rules.RulePackActivated.v1"
internal const val TRIPLE_TOPIC = "gst.masterdata.identity-triple-changed.v1"
internal const val TRIPLE_TYPE = "com.dynokore.gst.masterdata.IdentityTripleChanged.v1"
internal const val CERTIFIER = "identity:USR-C1"
internal const val CONFIG_ONLY = "identity:USR-C2"

/** The event-log reader of the certification tests: records per topic, offsets above every earlier test's. */
internal class TopicLog : EventLogReader {
    private val records = CopyOnWriteArrayList<Pair<String, LogRecord>>()

    fun add(
        topic: String,
        envelopeJson: String,
    ) {
        records += topic to LogRecord(OFFSETS.incrementAndGet(), envelopeJson)
    }

    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> =
        records
            .filter {
                it.first == topic && it.second.offset > afterOffset
            }.map { it.second }
            .take(limit)

    private companion object {
        // the offset rows of the consumers are platform-scoped and the database is shared by the tests
        val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000)
    }
}

/** The certification operations of the real application context, with the reads the tests assert on. */
internal class CertificationEnv(
    val env: ProfileEnv,
    private val log: TopicLog,
) {
    private val context: AssertableWebApplicationContext = env.context
    private val json = EnvelopeJson()
    private val mapper = JsonMapper.builder().build()
    val people: TestPeople = context.getBean(TestPeople::class.java)
    val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)

    /** The caller of the next calls holds exactly the permission [keys] on [entity]. */
    fun actAs(
        person: String,
        entity: EntityResponse,
        vararg keys: String,
    ) {
        people.caller = person
        keys.forEach { people.grantPermission(person, entity.entityRef, it) }
    }

    private val certifySandbox: CertifySandbox = context.getBean(CertifySandbox::class.java)

    /**
     * certifySandbox at the use-case seam: the generated `CertifySandboxRequest` of the pinned contract is a sealed
     * interface without `suiteVersion`, so the controller cannot be written yet (gap reported in the MR).
     */
    fun certify(
        entity: EntityResponse,
        outcome: CertificationOutcome = CertificationOutcome.PASSED,
        failed: List<String>? = null,
        suite: String = "2026.1",
    ): CertifyOutcome = certifySandbox.handle(people.caller, entity.entityRef, outcome, suite, failed)

    /** A certified entity: a PASSED record by a holder of the record key. */
    fun passed(entity: EntityResponse) {
        actAs(CERTIFIER, entity, RECORD_KEY)
        certify(entity)
    }

    /** The (status, trigger) row of the entity, or null when it has none. */
    fun state(entity: EntityResponse): Pair<String, String?>? =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select s.status, s.stale_trigger from tenancy.certification_status s " +
                        "join tenancy.entity e on e.tenant_id = s.tenant_id and e.id = s.entity_id where e.ref = ?",
                ).use { st ->
                    st.setString(1, entity.entityRef)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
                }
        }

    /** Every stored record row of the entity as JSON text, oldest first. */
    fun rows(entity: EntityResponse): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select row_to_json(r)::text from tenancy.sandbox_certification r " +
                        "join tenancy.entity e on e.tenant_id = r.tenant_id and e.id = r.entity_id " +
                        "where e.ref = ? order by r.recorded_at, r.id",
                ).use { st ->
                    st.setString(1, entity.entityRef)
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    /** The CertificationStatusChanged payloads in the outbox table for the entity: committed rows only. */
    fun stored(entity: EntityResponse): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select data::text from tenancy.outbox where topic = ? and data::text like ? order by 1",
                ).use { st ->
                    st.setString(1, STATUS_TOPIC)
                    st.setString(2, "%\"${entity.entityRef}\"%")
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    /** The `subject` the outbox stored for the entity's events: the aggregate key, which is the `entityRef`. */
    fun aggregateKeys(entity: EntityResponse): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select subject from tenancy.outbox where topic = ? and data::text like ?").use { st ->
                st.setString(1, STATUS_TOPIC)
                st.setString(2, "%\"${entity.entityRef}\"%")
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    fun payload(text: String): JsonNode = mapper.readTree(text)

    private fun envelope(
        type: String,
        tenant: TenantId,
        subject: String,
        data: String,
    ) = Envelope(
        EventId(UUID.randomUUID().toString()),
        "/gst/" + type.removePrefix("com.dynokore.gst.").substringBefore('.') + "-svc",
        type,
        subject,
        Instant.now(),
        tenant,
        EntityRef(subject),
        null,
        null,
        null,
        "test-0",
        CorrelationId("corr-${UUID.randomUUID()}"),
        CausationId("cause-${UUID.randomUUID()}"),
        data,
    )

    /** Appends the event to the log under [topic] (the topic the consumer reads, or a look-alike) and returns it. */
    fun publishToLog(
        topic: String,
        event: Envelope,
    ): Envelope {
        log.add(topic, json.write(event))
        return event
    }

    fun rulePackActivated(
        tenant: TenantId,
        topic: String = PACK_TOPIC,
        data: String = "{\"packIdentifier\":\"GST-RULES\",\"version\":\"2.0\",\"effectiveFrom\":\"2026-11-01\"}",
    ): Envelope = publishToLog(topic, envelope(PACK_TYPE, tenant, "GST-RULES", data))

    fun identityTripleChanged(
        tenant: TenantId,
        entityRef: String,
        topic: String = TRIPLE_TOPIC,
    ): Envelope =
        publishToLog(
            topic,
            envelope(
                TRIPLE_TYPE,
                tenant,
                entityRef,
                "{\"entityRef\":\"$entityRef\",\"constitution\":\"PRIVATE_LIMITED\",\"effectiveFrom\":\"2026-11-01\"," +
                    "\"changeRef\":\"CHG-${UUID.randomUUID().toString().take(8)}\"}",
            ),
        )

    /** Polls the wired consumer bean once; the result counts what it applied, ignored, repeated. */
    fun poll(consumerBean: String) = context.getBean(consumerBean, InboxConsumer::class.java).pollOnce()
}
