package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.EntityIdentityReader
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** INV-AC-21532: the EntityProfileChanged entity identity feed over the migrated schema (handler bean, real store). */
class EntityIdentityFeedTest {
    private val actor = SignedBindings.ACTOR
    private val t1 = TenantId(UUID.randomUUID())

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val dataSource: DataSource = context.getBean(DataSource::class.java)
        val handler: EventHandler = context.getBean("entityIdentityHandler", EventHandler::class.java)
    }

    private val i1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val i2 = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

    private fun data(
        id: String? = "\"$i1\"",
        version: String? = "8",
        kind: String = "ENTITY",
        extra: String = "",
    ): String =
        "{\"entityRef\":\"E-001\"$extra" + (if (id != null) ",\"entityId\":$id" else "") +
            ",\"node\":{\"kind\":\"$kind\"" + (if (version != null) ",\"structureVersion\":$version" else "") + "}}"

    private fun envelope(
        dataJson: String,
        tenant: TenantId = t1,
    ) = Envelope(
        id = EventId("evt-1"),
        source = "/gst/tenancy-svc",
        type = EntityIdentityFeed.EVENT_TYPE,
        subject = "E-001",
        time = Instant.parse("2026-07-01T00:00:00Z"),
        tenant = tenant,
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "i-1",
        correlation = CorrelationId("c-1"),
        causation = CausationId("k-1"),
        dataJson = dataJson,
    )

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun Boundary.handle(dataJson: String): Result<HandleOutcome> =
        scope.bind(t1, actor) { runner.inTransaction { runCatching { handler.handle(envelope(dataJson)) } } }

    private fun Boundary.handleAs(
        tenant: TenantId,
        dataJson: String,
    ): Result<HandleOutcome> =
        scope.bind(
            tenant,
            actor,
        ) { runner.inTransaction { runCatching { handler.handle(envelope(dataJson, tenant)) } } }

    private fun Boundary.idOf(tenant: TenantId): UUID? =
        scope.bind(
            tenant,
            actor,
        ) { runner.inTransaction { JooqEntityIdentities(dsl).entityIdOf(tenant.value, "E-001") } }

    @Test
    fun `INV-AC-21532 E1 stores the entity id`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data()).getOrThrow() shouldBe HandleOutcome.APPLIED
        b.idOf(t1) shouldBe i1
    }

    @Test
    fun `INV-AC-21532 E2 the same event again is acknowledged and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data()).getOrThrow() shouldBe HandleOutcome.APPLIED
        b.handle(data()).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.idOf(t1) shouldBe i1
    }

    @Test
    fun `INV-AC-21532 E3 a version not above the stored one changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data()).getOrThrow()
        b.handle(data(id = "\"$i2\"", version = "7")).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.idOf(t1) shouldBe i1
    }

    @Test
    fun `INV-AC-21532 E4 a higher version replaces the id`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data()).getOrThrow()
        b.handle(data(id = "\"$i2\"", version = "9")).getOrThrow() shouldBe HandleOutcome.APPLIED
        b.idOf(t1) shouldBe i2
    }

    @Test
    fun `INV-AC-21532 E5 an event without entityId never deletes or changes the stored id`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data()).getOrThrow()
        b.handle(data(id = null, version = "10")).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.idOf(t1) shouldBe i1
    }

    @Test
    fun `INV-AC-21532 E6 an entityId that is not a canonical uuid is a contract violation`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        listOf("\"E-001\"", "null", "7", "\"${i1.toString().uppercase()}\"").forEach {
            b.handle(data(id = it)).exceptionOrNull() as? ContractViolation shouldNotBe null
        }
        b.handle(data(version = null)).exceptionOrNull() as? ContractViolation shouldNotBe null
        b.handle(data(version = "0")).exceptionOrNull() as? ContractViolation shouldNotBe null
        b.idOf(t1) shouldBe null
    }

    @Test
    fun `INV-AC-21532 E7 a group node is acknowledged and applies nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(kind = "GROUP")).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.idOf(t1) shouldBe null
    }

    @Test
    fun `INV-AC-21532 E8 an envelope without tenantid is a contract violation`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        // The platform tenant is how the reader sees a missing tenantid (FR-K.3).
        assertThrows<ContractViolation> { EntityIdentityReader().read(data(), TenantId.PLATFORM) }
        b.idOf(t1) shouldBe null
    }

    @Test
    fun `INV-AC-21532 the id is answered per tenant and the tenant comes from the envelope not the data`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t2 = TenantId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
        b.handle(data(extra = ",\"tenantId\":\"${t2.value}\",\"tenantid\":\"${t2.value}\"")).getOrThrow()
        b.idOf(t1) shouldBe i1
        b.idOf(t2) shouldBe null
        // The same entityRef of another tenant is its own row (the key carries tenant_id).
        b.handleAs(t2, data(id = "\"$i2\"")).getOrThrow() shouldBe HandleOutcome.APPLIED
        b.idOf(t1) shouldBe i1
        b.idOf(t2) shouldBe i2
    }

    @Test
    fun `INV-AC-21532 a delete statement is not granted to the runtime role`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        fun priv(p: String) =
            b.dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select has_table_privilege('canonical_runtime', 'canonical.entity_identity', '$p')",
                        ).use {
                            it.next()
                            it.getBoolean(1)
                        }
                }
            }
        priv("DELETE") shouldBe false
        priv("INSERT") shouldBe true
    }

    @Test
    fun `INV-AC-21532 subscription lists exactly the topic and event type under its own consumer name`() {
        EntityIdentityFeed.TOPIC shouldBe "gst.tenancy.entity-profile-changed.v1"
        EntityIdentityFeed.EVENT_TYPE shouldBe "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
        (EntityIdentityFeed.CONSUMER == EntityConstitutionFeed.CONSUMER) shouldBe false
        (EntityIdentityFeed.CONSUMER == ProfileElectionFeed.CONSUMER) shouldBe false
        EntityIdentityFeed.CONSUMER shouldBe "canonical-svc-entity-identity"
    }
}
