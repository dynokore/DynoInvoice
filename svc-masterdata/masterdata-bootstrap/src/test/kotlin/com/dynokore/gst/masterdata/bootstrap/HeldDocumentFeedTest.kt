package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.ConsumedHeldDocumentEvents
import com.dynokore.gst.masterdata.adapters.events.MasterdataEventCatalogue
import com.dynokore.gst.masterdata.adapters.events.PublishedChannels
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-21335 (INV-T-21336): the DocumentHeld and DocumentReleased consumers wired by [HeldDocumentFeed] over real
 * PostgreSQL (as CounterpartyLinkTest wires SignedLinkFeed), the catalogue entries, the consumed channel names, the
 * operations rows and the imports file.
 */
class HeldDocumentFeedTest {
    private fun run(block: (Env) -> Unit) {
        BoundaryFixture
            .runner()
            .withConfiguration(AutoConfigurations.of(HeldDocumentFeed::class.java))
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val held: HeldDocuments = context.getBean(HeldDocuments::class.java)
        val heldHandler: EventHandler = context.getBean("documentHeldHandler", EventHandler::class.java)
        val releasedHandler: EventHandler = context.getBean("documentReleasedHandler", EventHandler::class.java)
        val t1 = TenantId(UUID.randomUUID())
        val t2 = TenantId(UUID.randomUUID())
        val entity = "E-HELD-" + UUID.randomUUID().toString().take(8)
        val party = "CTP-" + UUID.randomUUID().toString().take(8)
        val d1: UUID = UUID.randomUUID()
        val d2: UUID = UUID.randomUUID()

        fun envelope(
            type: String,
            data: String,
            of: TenantId = t1,
        ) = Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/canonical",
            type = type,
            subject = "document",
            time = Instant.now(),
            tenant = of,
            entity = EntityRef(entity),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("c-1"),
            causation = CausationId("c-1"),
            dataJson = data,
        )

        fun heldData(
            doc: Any,
            seq: Any,
            entityRef: String? = entity,
            partyRef: String? = null,
        ): String {
            val parts =
                listOfNotNull(
                    """"docUuid":"$doc"""",
                    """"docsequence":$seq""",
                    """"holdReasons":["MDM-001"]""",
                    entityRef?.let { """"entityRef":"$it"""" },
                    partyRef?.let { """"partyRef":"$it"""" },
                )
            return "{" + parts.joinToString(",") + "}"
        }

        fun releasedData(
            doc: Any,
            seq: Any,
        ) = """{"docUuid":"$doc","docsequence":$seq,"releasedHoldReasons":["MDM-001"]}"""

        fun hold(
            data: String,
            of: TenantId = t1,
            type: String = HELD,
        ): HandleOutcome = tenants.bind(of, OPERATOR) { heldHandler.handle(envelope(type, data, of)) }

        fun release(
            data: String,
            of: TenantId = t1,
            type: String = RELEASED,
        ): HandleOutcome = tenants.bind(of, OPERATOR) { releasedHandler.handle(envelope(type, data, of)) }

        fun heldFor(of: TenantId): List<UUID> =
            tenants.bind(of, OPERATOR) { tx.inTransaction { held.heldFor(entity, PartyRef(party)) } }

        /** The rows of both documents as (docsequence, held), read as the superuser so that every row is seen. */
        fun rows(): Map<UUID, Pair<Long, Boolean>> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select doc_uuid, docsequence, held from masterdata.held_document " +
                            "where tenant_id = ?::uuid or tenant_id = ?::uuid",
                    ).use { s ->
                        s.setString(1, t1.value.toString())
                        s.setString(2, t2.value.toString())
                        s.executeQuery().use { rs ->
                            buildMap {
                                while (rs.next()) {
                                    put(rs.getObject(1, UUID::class.java), rs.getLong(2) to rs.getBoolean(3))
                                }
                            }
                        }
                    }
            }
    }

    @Test
    fun `INV-AC-21335 a hold records the document under the party and a repeat leaves the row equal`() =
        run { env ->
            assertThat(env.hold(env.heldData(env.d1, 1, partyRef = env.party))).isEqualTo(HandleOutcome.APPLIED)
            assertThat(env.heldFor(env.t1)).containsExactly(env.d1)
            val repeat = env.hold(env.heldData(env.d1, 1, partyRef = env.party))
            assertThat(repeat).isIn(HandleOutcome.APPLIED, HandleOutcome.IGNORED)
            assertThat(env.rows()).containsOnly(entry(env.d1, 1L to true))
            assertThat(env.heldFor(env.t2)).isEmpty()
        }

    @Test
    fun `INV-AC-21335 a hold without a party on no row writes no row and a higher docsequence wins`() =
        run { env ->
            env.hold(env.heldData(env.d2, 2))
            assertThat(env.rows()).doesNotContainKey(env.d2)
            env.hold(env.heldData(env.d1, 1, partyRef = env.party))
            env.release(env.releasedData(env.d1, 2))
            assertThat(env.heldFor(env.t1)).isEmpty()
            assertThat(env.rows()).containsOnly(entry(env.d1, 2L to false))
            env.hold(env.heldData(env.d1, 1, partyRef = env.party))
            assertThat(env.rows()).containsOnly(entry(env.d1, 2L to false))
        }

    @Test
    fun `INV-AC-21335 a release before its hold leaves the document released with its docsequence`() =
        run { env ->
            env.release(env.releasedData(env.d2, 3))
            env.hold(env.heldData(env.d2, 2, partyRef = env.party))
            assertThat(env.rows()).containsOnly(entry(env.d2, 3L to false))
            assertThat(env.heldFor(env.t1)).isEmpty()
        }

    @Test
    fun `INV-AC-21335 a payload that breaks the contract or names another tenant is parked and writes nothing`() =
        run { env ->
            val long = "P".repeat(41)
            val held =
                listOf(
                    env.heldData("not-a-uuid", 1, partyRef = env.party),
                    env.heldData(env.d1, 0, partyRef = env.party),
                    env.heldData(env.d1, 1, partyRef = long),
                    env.heldData(env.d1, 1, entityRef = null, partyRef = env.party),
                    env.heldData(env.d1, 1, entityRef = "E".repeat(41), partyRef = env.party),
                    env.heldData(env.d1, 1.5, partyRef = env.party),
                    env.heldData(env.d1, 1, partyRef = env.party).dropLast(1) + ""","tenant":"${env.t2}"}""",
                )
            held.forEach { data ->
                assertThatThrownBy { env.hold(data) }.describedAs(data).isInstanceOf(ContractViolation::class.java)
            }
            listOf(
                env.releasedData("not-a-uuid", 2),
                env.releasedData(env.d1, 0),
                """{"docUuid":"${env.d1}","docsequence":2}""",
            ).forEach { data ->
                assertThatThrownBy { env.release(data) }.describedAs(data).isInstanceOf(ContractViolation::class.java)
            }
            assertThat(env.rows()).isEmpty()
        }

    @Test
    fun `INV-AC-21335 a handler given the other handler's type fails its precondition and writes nothing`() =
        run { env ->
            assertThatThrownBy { env.hold(env.heldData(env.d1, 1, partyRef = env.party), type = RELEASED) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { env.release(env.releasedData(env.d1, 2), type = HELD) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(env.rows()).isEmpty()
        }

    @Test
    fun `INV-AC-21335 the catalogue lists both event types with their topics and the emitting service`() {
        val entries = MasterdataEventCatalogue.load().entries().associateBy { it.eventType }
        val signed = entries.getValue("com.dynokore.gst.access.SignedLinkIssued.v1")
        mapOf(
            HELD to "gst.canonical.document-held.v1",
            RELEASED to "gst.canonical.document-released.v1",
        ).forEach { (type, topic) ->
            val entry = entries.getValue(type)
            assertThat(entry.topic).isEqualTo(topic)
            assertThat(entry.producers).containsExactly("canonical-svc")
            assertThat(entry.contentTopic).isEqualTo(signed.contentTopic)
        }
        assertThat(signed.producers).containsExactly("access-svc")
        assertThat(ConsumedHeldDocumentEvents.SUBSCRIPTIONS)
            .containsOnly(
                entry(HELD, "gst.canonical.document-held.v1"),
                entry(RELEASED, "gst.canonical.document-released.v1"),
            )
    }

    @Test
    fun `INV-AC-21335 the consumed channels are exactly the four names and ALL is unchanged`() {
        assertThat(PublishedChannels.CONSUMED)
            .containsExactlyInAnyOrder(
                "accessSignedLinkIssued",
                "accessSignedLinkInvalidated",
                "canonicalDocumentHeld",
                "canonicalDocumentReleased",
            )
        assertThat(PublishedChannels.ALL).doesNotContain("canonicalDocumentHeld", "canonicalDocumentReleased")
        assertThat(PublishedChannels.IMPLEMENTED).isEqualTo(PublishedChannels.ALL + PublishedChannels.CONSUMED)
    }

    @Test
    fun `INV-AC-21335 operations rows are done under this task and the imports file lists four feeds`() {
        val rows = File("../operations.tsv").readLines().map { it.split('\t') }
        listOf("canonicalDocumentHeld", "canonicalDocumentReleased").forEach { name ->
            assertThat(rows.filter { it.getOrNull(1) == name })
                .containsExactly(listOf("channel", name, "INV-T-21336", "done"))
        }
        assertThat(File("../done-tasks.txt").readLines().count { it == "INV-T-21336" }).isEqualTo(1)
        val imports =
            HeldDocumentFeed::class.java.classLoader
                .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .toList()
                .filter { it.path.contains("masterdata-bootstrap") }
                .flatMap { File(it.toURI()).readLines() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
        assertThat(imports)
            .containsExactly(
                "com.dynokore.gst.masterdata.bootstrap.StructureFeed",
                "com.dynokore.gst.masterdata.bootstrap.RatePackFeed",
                "com.dynokore.gst.masterdata.bootstrap.SignedLinkFeed",
                "com.dynokore.gst.masterdata.bootstrap.HeldDocumentFeed",
            )
    }

    private companion object {
        const val HELD = "com.dynokore.gst.canonical.DocumentHeld.v1"
        const val RELEASED = "com.dynokore.gst.canonical.DocumentReleased.v1"
        const val OPERATOR = "IDN-OPERATOR"
    }
}
