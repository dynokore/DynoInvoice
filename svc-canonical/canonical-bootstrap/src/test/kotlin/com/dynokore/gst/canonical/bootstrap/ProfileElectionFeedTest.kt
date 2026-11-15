package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.ProfileElectionReader
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PROFILE_ELECTION
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

/** INV-AC-21466: the EntityProfileChanged election feed over the migrated schema (handler bean, real store). */
class ProfileElectionFeedTest {
    private val actor = SignedBindings.ACTOR
    private val t1 = TenantId(UUID.randomUUID())

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val handler: EventHandler = context.getBean("profileElectionHandler", EventHandler::class.java)
    }

    private fun item(
        attribute: String,
        value: String,
        date: String,
    ) = "{\"attribute\":\"$attribute\",\"value\":\"$value\",\"effectiveDate\":\"$date\"}"

    private val basis = item("purchaseDateBasis", "POSTING_DATE", "2026-07-01")
    private val arcs = item("gstScheme.ARCS", "ON", "2026-01-01")
    private val issuance = item("issuanceMode", "RELAY", "2026-01-01")

    private fun data(
        profile: List<String>,
        version: String? = "8",
        kind: String = "ENTITY",
        extra: String = "",
    ): String =
        "{\"entityRef\":\"E-001\"$extra,\"node\":{\"kind\":\"$kind\"" +
            (if (version != null) ",\"structureVersion\":$version" else "") +
            ",\"profile\":[${profile.joinToString(",")}]}}"

    private fun envelope(dataJson: String) =
        Envelope(
            id = EventId("evt-1"),
            source = "/gst/tenancy-svc",
            type = ProfileElectionFeed.EVENT_TYPE,
            subject = "E-001",
            time = Instant.parse("2026-07-01T00:00:00Z"),
            tenant = t1,
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

    private fun Boundary.rows(tenant: TenantId = t1): List<String> =
        scope.bind(tenant, actor) {
            runner.inTransaction {
                dsl
                    .selectFrom(PROFILE_ELECTION)
                    .where(PROFILE_ELECTION.TENANT_ID.eq(tenant.value))
                    .orderBy(PROFILE_ELECTION.ATTRIBUTE)
                    .fetch()
                    .map { "${it.attribute}=${it.value}@${it.effectiveDate}#${it.version}" }
            }
        }

    private val applied1 = listOf("gstScheme.ARCS=ON@2026-01-01#8", "purchaseDateBasis=POSTING_DATE@2026-07-01#8")

    @Test
    fun `INV-AC-21466 E1 applies exactly the two election entries and not issuanceMode`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(listOf(basis, arcs, issuance))).getOrThrow() shouldBe HandleOutcome.APPLIED
        b.rows() shouldBe applied1
    }

    @Test
    fun `INV-AC-21466 E2 the same event again is acknowledged and changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(listOf(basis, arcs, issuance))).getOrThrow() shouldBe HandleOutcome.APPLIED
        b.handle(data(listOf(basis, arcs, issuance))).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.rows() shouldBe applied1
    }

    @Test
    fun `INV-AC-21466 E3 a version not above the stored one changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(listOf(basis, arcs, issuance))).getOrThrow()
        val older = item("purchaseDateBasis", "INVOICE_DATE", "2026-07-01")
        b.handle(data(listOf(older), version = "7")).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.rows() shouldBe applied1
    }

    @Test
    fun `INV-AC-21466 E4 no election attribute is acknowledged and applies nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(listOf(issuance))).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21466 E5 a basis value other than the two is a contract violation`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val bad = item("purchaseDateBasis", "LAST_DAY", "2026-07-01")
        b.handle(data(listOf(bad, arcs))).exceptionOrNull() as? ContractViolation shouldNotBe null
        b.rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21466 E6 a missing structureVersion is a contract violation`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(listOf(basis), version = null)).exceptionOrNull() as? ContractViolation shouldNotBe null
        b.handle(data(listOf(basis), version = "0")).exceptionOrNull() as? ContractViolation shouldNotBe null
        b.handle(data(listOf(basis), version = "\"8\"")).exceptionOrNull() as? ContractViolation shouldNotBe null
        b.rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21466 E7 a group node is acknowledged and applies nothing`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        b.handle(data(listOf(basis), kind = "GROUP")).getOrThrow() shouldBe HandleOutcome.IGNORED
        b.rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21466 E8 an envelope without tenantid is a contract violation`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        // An Envelope cannot carry a domain event without a tenant (FR-K.3); the platform tenant is how the reader
        // sees a missing tenantid, so the reader is called with it.
        assertThrows<ContractViolation> { ProfileElectionReader().read(data(listOf(basis)), TenantId.PLATFORM) }
        b.rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21466 the tenant comes from the envelope not from the data`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val other = TenantId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        b
            .handle(data(listOf(basis), extra = ",\"tenantId\":\"${other.value}\",\"tenantid\":\"${other.value}\""))
            .getOrThrow() shouldBe HandleOutcome.APPLIED
        b.rows(t1) shouldBe listOf("purchaseDateBasis=POSTING_DATE@2026-07-01#8")
        b.rows(other) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21466 subscription lists exactly the topic and event type under its own consumer name`() {
        ProfileElectionFeed.TOPIC shouldBe "gst.tenancy.entity-profile-changed.v1"
        ProfileElectionFeed.EVENT_TYPE shouldBe "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
        (ProfileElectionFeed.CONSUMER == EntityConstitutionFeed.CONSUMER) shouldBe false
        ProfileElectionFeed.CONSUMER shouldBe "canonical-svc-profile-election"
    }
}
