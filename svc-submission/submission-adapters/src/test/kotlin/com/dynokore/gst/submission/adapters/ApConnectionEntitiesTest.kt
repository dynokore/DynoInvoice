package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.AccessPointRouteChangedHandler
import com.dynokore.gst.submission.adapters.persistence.JooqApConnectionEntities
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The connection replica and its handler against the test database as `submission_runtime` (INV-AC-23662). */
class ApConnectionEntitiesTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                }
            }
        }

    private val store = JooqApConnectionEntities(dsl, runner)

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(checkNotNull(bound.get()))

            override fun currentActor(): String = SignedBindings.ACTOR

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = throw UnsupportedOperationException()
        }

    private val handler = AccessPointRouteChangedHandler(tenants, store)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    private fun event(
        tenant: UUID,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/transport-svc",
        type = "com.dynokore.gst.transport.AccessPointRouteChanged.v1",
        subject = "C",
        time = Instant.parse("2026-11-16T08:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E"),
        docUuid = null,
        transactionDate = null,
        docSequence = 1,
        instanceId = "transport-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun deliver(
        tenant: UUID,
        connectionRef: String,
        version: Long,
        vararg entities: String,
    ): HandleOutcome {
        val list = entities.joinToString(",") { "\"$it\"" }
        val data = """{"connectionRef":"$connectionRef","entityRefs":[$list],"version":$version}"""
        return asTenant(tenant) { handler.handle(event(tenant, data)) }
    }

    private fun connectionOf(
        tenant: UUID,
        entity: String,
    ) = asTenant(tenant) { store.connectionOf(TenantId(tenant), entity) }

    @Test
    fun `INV-AC-23662 connectionRef of the assigned connection is read`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 1, "E", "F") shouldBe HandleOutcome.APPLIED
        connectionOf(a, "E") shouldBe "C"
        connectionOf(a, "F") shouldBe "C"
    }

    @Test
    fun `INV-AC-23662 an unassigned entity has no connection and another tenant reads nothing`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        deliver(a, "C", 1, "E")
        connectionOf(a, "X") shouldBe null
        connectionOf(b, "E") shouldBe null
    }

    @Test
    fun `INV-AC-23662 an entity of another tenant with the same ref is not read`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        deliver(a, "C", 1, "E")
        asTenant(a) { store.connectionOf(TenantId(b), "E") } shouldBe null
        asTenant(a) { store.connectionOf(TenantId(a), "E") } shouldBe "C"
    }

    @Test
    fun `INV-AC-23662 an older route version of the same connection is ignored`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 5, "E") shouldBe HandleOutcome.APPLIED
        deliver(a, "C", 4, "F") shouldBe HandleOutcome.IGNORED
        connectionOf(a, "E") shouldBe "C"
        connectionOf(a, "F") shouldBe null
    }

    @Test
    fun `INV-AC-23662 a repeated version of the same connection is ignored`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 5, "E") shouldBe HandleOutcome.APPLIED
        deliver(a, "C", 5) shouldBe HandleOutcome.IGNORED
        connectionOf(a, "E") shouldBe "C"
    }

    @Test
    fun `INV-AC-23662 an entity removed from the list is deleted`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 1, "E", "F")
        deliver(a, "C", 2, "F") shouldBe HandleOutcome.APPLIED
        connectionOf(a, "E") shouldBe null
        connectionOf(a, "F") shouldBe "C"
        deliver(a, "C", 3) shouldBe HandleOutcome.APPLIED
        connectionOf(a, "F") shouldBe null
    }

    @Test
    fun `INV-AC-23662 a move delivered destination first keeps the entity at the destination`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 4, "E")
        deliver(a, "D", 3, "E") shouldBe HandleOutcome.APPLIED
        deliver(a, "C", 5) shouldBe HandleOutcome.APPLIED
        connectionOf(a, "E") shouldBe "D"
    }

    @Test
    fun `INV-AC-23662 a move delivered source first keeps the entity at the destination`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 4, "E")
        deliver(a, "C", 5) shouldBe HandleOutcome.APPLIED
        deliver(a, "D", 3, "E") shouldBe HandleOutcome.APPLIED
        connectionOf(a, "E") shouldBe "D"
    }

    @Test
    fun `INV-AC-23662 a stale version of the source after the move changes nothing`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 4, "E")
        deliver(a, "D", 3, "E")
        deliver(a, "C", 5)
        deliver(a, "C", 4, "E") shouldBe HandleOutcome.IGNORED
        connectionOf(a, "E") shouldBe "D"
    }

    @Test
    fun `INV-AC-23662 a version of another connection is not compared`() {
        val a = UUID.randomUUID()
        deliver(a, "C", 9, "E")
        deliver(a, "D", 2, "E") shouldBe HandleOutcome.APPLIED
        connectionOf(a, "E") shouldBe "D"
    }

    @Test
    fun `INV-AC-23662 a malformed event is a contract violation and stores nothing`() {
        val a = UUID.randomUUID()
        val bad =
            listOf(
                """{"entityRefs":[],"version":1}""",
                """{"connectionRef":"C","version":1}""",
                """{"connectionRef":"C","entityRefs":["E"]}""",
                """{"connectionRef":"C","entityRefs":["E"],"version":0}""",
                """{"connectionRef":"C","entityRefs":[""],"version":1}""",
                """{"connectionRef":"C","entityRefs":["E"],"version":"1"}""",
                """[]""",
                "not json",
            )
        bad.forEach { data -> shouldThrow<ContractViolation> { asTenant(a) { handler.handle(event(a, data)) } } }
        connectionOf(a, "E") shouldBe null
    }
}
