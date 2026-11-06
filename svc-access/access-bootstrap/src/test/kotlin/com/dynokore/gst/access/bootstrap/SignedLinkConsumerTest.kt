package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.SignedLinkRequestedHandler
import com.dynokore.gst.access.application.link.TEMPLATE_SIGNED_LINK
import com.dynokore.gst.access.application.link.TEMPLATE_SIGNED_LINK_PASSCODE
import com.dynokore.gst.access.domain.link.LinkSource
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * The consumer of `SignedLinkRequested` (INV-T-17453; INV-REQ-899, rulings INV-Q-17416 B, INV-Q-17205 A,
 * INV-Q-17207 A):
 * the topic is delivered through the inbox consumer of the real context over a real PostgreSQL. The stored links and
 * the `MessageRequested` rows of the outbox are read back by a superuser.
 */
class SignedLinkConsumerTest {
    private val messageType = "com.dynokore.gst.access.MessageRequested.v1"

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val feed: TopicFeed,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val clock = context.getBean(java.time.Clock::class.java) as MovableClock
        val consumer = context.getBean("signedLinkRequestedConsumer", InboxConsumer::class.java)
        val entity = "ENV-${UUID.randomUUID().toString().take(8)}"

        fun payload(
            expiry: String? = clock.instant().plusSeconds(3600).toString(),
            email: String? = "signatory@client.example",
            mobile: String? = "+6591234567",
            extra: String = "",
        ): String {
            val recipient =
                listOfNotNull(
                    email?.let { """"email":"$it"""" },
                    mobile?.let { """"mobileNumber":"$it"""" },
                ).joinToString(",")
            val parts =
                listOfNotNull(
                    """"purpose":"CONFIRM_FILING"""",
                    """"subjectReference":"SUBJ-1"""",
                    """"recipient":{$recipient}""",
                    expiry?.let { """"expiry":"$it"""" },
                )
            return "{" + parts.joinToString(",") + extra + "}"
        }

        fun envelope(
            data: String,
            id: String = UUID.randomUUID().toString(),
            source: String = "/gst/masterdata-svc",
        ) = Envelope(
            id = EventId(id),
            source = source,
            type = SignedLinkRequestedHandler.TYPE,
            subject = "signed-link",
            time = clock.instant(),
            tenant = tenant,
            entity = EntityRef(entity),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "masterdata-svc-0",
            correlation = CorrelationId("c-$id"),
            causation = CausationId("c-$id"),
            dataJson = data,
        )

        fun put(envelope: Envelope) = feed.put(envelope, SignedLinkRequestedHandler.TOPIC)

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

        fun links(): List<Map<String, String?>> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select source, source_event_id, alert_entity, client_ref, consent_ref, purpose, " +
                            "subject_reference, recipient_email, recipient_mobile, passcode_digest " +
                            "from access.signed_link " +
                            "where tenant_id = ? order by created_at",
                    ).use { s ->
                        s.setObject(1, UUID.fromString(tenant.toString()))
                        s.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        listOf(
                                            "source",
                                            "eventId",
                                            "alert",
                                            "client",
                                            "consent",
                                            "purpose",
                                            "subject",
                                            "email",
                                            "mobile",
                                            "digest",
                                        ).withIndex().associate { (i, name) -> name to rs.getString(i + 1) },
                                    )
                                }
                            }
                        }
                    }
            }

        /** The `MessageRequested` payloads of the tenant, as `channel|recipient|link or sealed`, in write order. */
        fun messages(): List<String> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select data from access.outbox where tenant_id = ?::uuid and type = ? order by id",
                    ).use { s ->
                        s.setString(1, tenant.toString())
                        s.setString(2, messageType)
                        s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
            }

        fun alertsAndAudits(): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement("select count(*) from access.outbox where tenant_id = ?::uuid")
                    .use { s ->
                        s.setString(1, tenant.toString())
                        s.executeQuery().use {
                            it.next()
                            it.getInt(1)
                        }
                    }
            }
    }

    private fun run(block: (Env) -> Unit) {
        val feed = TopicFeed()
        BoundaryFixture
            .runner(catalogueMatrix = true, requirements = null)
            .withInitializer { ctx ->
                (ctx as org.springframework.context.support.GenericApplicationContext).apply {
                    registerBean(TopicFeed::class.java, java.util.function.Supplier { feed })
                    registerBean(
                        RebuildRequiredSink::class.java,
                        java.util.function.Supplier { RebuildRequiredSink { } },
                    )
                }
            }.run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, feed))
            }
    }

    @Test
    fun `INV-REQ-899 a valid payload gives exactly one link and exactly two MessageRequested`() =
        run { env ->
            val id = UUID.randomUUID().toString()
            env.put(env.envelope(env.payload(), id))
            val result = env.deliver()
            assertThat(result.applied).isEqualTo(1)
            val link = env.links().single()
            assertThat(link["source"]).isEqualTo(LinkSource.EVENT.name)
            assertThat(link["eventId"]).isEqualTo(id)
            assertThat(link["alert"]).isEqualTo(env.entity)
            assertThat(link["client"]).isNull()
            assertThat(link["consent"]).isNull()
            assertThat(link["purpose"]).isEqualTo("CONFIRM_FILING")
            assertThat(link["subject"]).isEqualTo("SUBJ-1")
            assertThat(link["email"]).isEqualTo("signatory@client.example")
            assertThat(link["mobile"]).isEqualTo("+6591234567")
            val messages = env.messages()
            assertThat(messages).hasSize(2)
            val mapper =
                tools.jackson.databind.json
                    .JsonMapper()
            val first = mapper.readTree(messages[0])
            assertThat(first.get("template").asString()).isEqualTo(TEMPLATE_SIGNED_LINK)
            assertThat(first.get("linkReference").asString()).startsWith("LNK-")
            assertThat(first.has("sealedPasscode")).isFalse()
            val second = mapper.readTree(messages[1])
            assertThat(second.get("template").asString()).isEqualTo(TEMPLATE_SIGNED_LINK_PASSCODE)
            assertThat(second.get("channel").asString()).isEqualTo("SMS")
            assertThat(second.get("recipients")[0].asString()).isEqualTo("+6591234567")
            assertThat(second.get("sealedPasscode").asString()).startsWith("v1.")
            assertThat(second.has("linkReference")).isFalse()
        }

    @Test
    fun `INV-REQ-899 the same event delivered twice still gives one link and two messages`() =
        run { env ->
            val id = UUID.randomUUID().toString()
            env.put(env.envelope(env.payload(), id))
            env.put(env.envelope(env.payload(), id))
            env.deliver()
            assertThat(env.links()).hasSize(1)
            assertThat(env.messages()).hasSize(2)
        }

    @Test
    fun `INV-REQ-899 a payload without expiry or without recipient email gives no link and no message`() =
        run { env ->
            listOf(env.payload(expiry = null), env.payload(email = null), env.payload(expiry = "tomorrow")).forEach {
                env.put(env.envelope(it))
            }
            val result = env.deliver()
            assertThat(result.applied).isEqualTo(0)
            assertThat(env.links()).isEmpty()
            assertThat(env.messages()).isEmpty()
        }

    @Test
    fun `INV-REQ-899 an event from a service that is not the producer or with an expiry in the past gives nothing`() =
        run { env ->
            env.put(env.envelope(env.payload(expiry = Instant.now().minusSeconds(60).toString())))
            env.put(env.envelope(env.payload(), source = "/gst/ingestion-svc"))
            env.deliver()
            assertThat(env.links()).isEmpty()
            assertThat(env.messages()).isEmpty()
        }

    @Test
    fun `INV-REQ-899 a forced failure after the link row leaves no link and no outbox row`() =
        run { env ->
            val tenants = context(env).getBean(TenantContext::class.java)
            val tx = context(env).getBean(TransactionRunner::class.java)
            val handler = context(env).getBean(SignedLinkRequestedHandler::class.java)
            val failure =
                runCatching {
                    tenants.bind(env.tenant, "system:test") {
                        tx.inTransaction {
                            handler.handle(env.envelope(env.payload()))
                            throw SQLException("forced failure after the link and its messages")
                        }
                    }
                }
            assertThat(failure.exceptionOrNull()).isInstanceOf(SQLException::class.java)
            assertThat(env.links()).isEmpty()
            assertThat(env.alertsAndAudits()).isEqualTo(0)
            assertThat(env.messages()).isEmpty()
        }

    private fun context(env: Env) = env.context

    @Test
    fun `INV-REQ-899 a second link row with the same source event id is refused by the constraint`() =
        run { env ->
            val id = "evt-" + UUID.randomUUID()

            fun insert(ref: String) =
                BoundaryFixture.asSuperuser { c ->
                    c
                        .prepareStatement(
                            "insert into access.signed_link (id, tenant_id, link_ref, purpose, alert_entity, " +
                                "recipient_email, expires_at, passcode_digest, status, wrong_entries, source, " +
                                "source_event_id, created_at, created_by, updated_at, updated_by, version) values " +
                                "(?, ?, ?, 'P', 'ENV-1', 'a@b.example', now() + interval '1 hour', ?, 'ACTIVE', 0, " +
                                "'EVENT', ?, now(), 'test', now(), 'test', 0)",
                        ).use { s ->
                            s.setObject(1, UUID.randomUUID())
                            s.setObject(2, UUID.fromString(env.tenant.toString()))
                            s.setString(3, ref)
                            s.setString(4, "0".repeat(64))
                            s.setString(5, id)
                            s.executeUpdate()
                        }
                }
            insert("LNK-FIRST-" + UUID.randomUUID().toString().take(8))
            val second = runCatching { insert("LNK-SECOND-" + UUID.randomUUID().toString().take(8)) }.exceptionOrNull()
            assertThat(second).isInstanceOf(SQLException::class.java)
            assertThat((second as SQLException).sqlState).isEqualTo("23505")
            assertThat(second.message).contains("signed_link_source_event_key")
            assertThat(env.links()).hasSize(1)
        }
}
