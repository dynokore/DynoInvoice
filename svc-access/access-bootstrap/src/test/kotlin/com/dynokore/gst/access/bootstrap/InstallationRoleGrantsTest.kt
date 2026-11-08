package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.InstallationGrantRef
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.INSTALLATION_ROLE_GRANT
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * INV-T-9235 (INV-Q-8972 A, INV-Q-9229 A): the configured `access.installation-roles` published as INSTALLATION role
 * grants. Each start is a Spring context over the real database; the table and the outbox are the real ones, the
 * events are read from the capturing writer that delegates to the real outbox writer.
 */
class InstallationRoleGrantsTest {
    private val json = JsonMapper.builder().build()
    private val table = "access.installation_role_grant"
    private val type = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType

    private fun entries(vararg pairs: Pair<String, String>): Array<String> =
        if (pairs.isEmpty()) {
            arrayOf("access.installation-roles=")
        } else {
            listed(pairs)
        }

    private fun listed(pairs: Array<out Pair<String, String>>): Array<String> =
        pairs
            .flatMapIndexed { index, (subject, role) ->
                listOf(
                    "access.installation-roles[$index].subject=$subject",
                    "access.installation-roles[$index].role=$role",
                )
            }.toTypedArray()

    private val op1 = "user:op-1" to "deployment.operator"
    private val eng1 = "user:eng-1" to "platform.engineer"

    /** One start over the entries; the table is kept from the start before (never cleared here). */
    private fun start(
        vararg roles: Pair<String, String>,
        form: String = "PRIVATE",
        block: (AssertableWebApplicationContext) -> Unit = {},
    ): List<Envelope> {
        var events = emptyList<Envelope>()
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                form = form,
                tokenProperties = TestSecrets.TOKEN_PROPERTIES + entries(*roles),
            ).run { context ->
                context.startupFailure?.let { throw it }
                events = context.getBean(CapturedEvents::class.java).envelopes.filter { it.type == type }
                block(context)
            }
        return events
    }

    private fun reset() =
        BoundaryFixture.asSuperuser {
            it.createStatement().use { s -> s.execute("delete from access.installation_role_grant") }
            it.createStatement().use { s ->
                s.execute("delete from access.outbox where tenant_id = '${TenantId.PLATFORM}'")
            }
        }

    private fun rows(): Map<String, Triple<String, String, Long>> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select grant_ref, role, state, authority_version from $table").use {
                    buildMap {
                        while (it.next()) put(it.getString(1), Triple(it.getString(2), it.getString(3), it.getLong(4)))
                    }
                }
            }
        }

    private fun record(event: Envelope): JsonNode = json.readTree(event.dataJson)

    private fun version(event: Envelope) = record(event)["authorityVersion"].asLong()

    private fun state(event: Envelope) = record(event)["roleGrantRecord"]["state"].asString()

    @Test
    fun `INV-AC-9212 the first start with two entries appends two ACTIVE events and stores two ACTIVE rows`() {
        reset()
        val events = start(op1, eng1)
        assertThat(events).hasSize(2)
        assertThat(events.map { state(it) }).containsOnly("ACTIVE")
        assertThat(events.map { it.subject }).containsExactlyInAnyOrder("user:op-1", "user:eng-1")
        events.forEach {
            val node = record(it)
            assertThat(node["changeKind"].asString()).isEqualTo("ROLE_GRANT")
            assertThat(node["roleGrantRecord"]["scopeLevel"].asString()).isEqualTo("INSTALLATION")
            assertThat(node["roleGrantRecord"].has("scopeRef")).describedAs("no scopeRef").isFalse()
            assertThat(node["principalRef"].asString()).isEqualTo(it.subject)
        }
        assertThat(rows().values.map { it.second }).containsOnly("ACTIVE")
        assertThat(rows().values.map { it.third }).containsExactlyInAnyOrderElementsOf(events.map { version(it) })
        assertThat(rows().keys).containsExactlyInAnyOrder(
            InstallationGrantRef.of("deployment.operator", "user:op-1"),
            InstallationGrantRef.of("platform.engineer", "user:eng-1"),
        )
    }

    @Test
    fun `INV-AC-9212 a second start with the same entries appends no event and changes no row`() {
        reset()
        start(op1, eng1)
        val before = rows()
        assertThat(start(op1, eng1)).isEmpty()
        assertThat(rows()).isEqualTo(before)
    }

    @Test
    fun `INV-AC-9212 a removed entry gives one REMOVED event above the first version, re-adding it gives ACTIVE`() {
        reset()
        val first = start(op1, eng1).associateBy { it.subject }
        val removed = start(op1)
        assertThat(removed).hasSize(1)
        assertThat(removed.single().subject).isEqualTo("user:eng-1")
        assertThat(state(removed.single())).isEqualTo("REMOVED")
        assertThat(version(removed.single())).isGreaterThan(version(first.getValue("user:eng-1")))
        assertThat(rows().getValue(InstallationGrantRef.of("platform.engineer", "user:eng-1")).second)
            .isEqualTo("REMOVED")
        assertThat(rows().getValue(InstallationGrantRef.of("deployment.operator", "user:op-1")).second)
            .isEqualTo("ACTIVE")
        // the same start again changes nothing, a REMOVED row is not removed twice
        assertThat(start(op1)).isEmpty()
        val added = start(op1, eng1)
        assertThat(added).hasSize(1)
        assertThat(state(added.single())).isEqualTo("ACTIVE")
        assertThat(version(added.single())).isGreaterThan(version(removed.single()))
        assertThat(rows().values.map { it.second }).containsOnly("ACTIVE")
    }

    @Test
    fun `INV-AC-9212 an empty list with no rows writes nothing`() {
        reset()
        assertThat(start()).isEmpty()
        assertThat(rows()).isEmpty()
    }

    @Test
    fun `an entry with the role tenant admin refuses the start and writes nothing`() {
        reset()
        var failure: Throwable? = null
        BoundaryFixture
            .runner(tokenProperties = TestSecrets.TOKEN_PROPERTIES + entries("user:x" to "tenant.admin"))
            .run { failure = it.startupFailure }
        assertThat(failure).isNotNull()
        assertThat(generateSequence(failure) { it.cause }.map { it.message.orEmpty() }.toList())
            .anyMatch { it.contains("access.installation-roles") }
        assertThat(rows()).isEmpty()
    }

    @Test
    fun `subjects that differ by case or a trailing space and roles of one subject get different grant refs`() {
        val refs =
            listOf(
                InstallationGrantRef.of("deployment.operator", "user:op-1"),
                InstallationGrantRef.of("deployment.operator", "USER:OP-1"),
                InstallationGrantRef.of("deployment.operator", "user:op-1 "),
                InstallationGrantRef.of("platform.engineer", "user:op-1"),
            )
        assertThat(refs.toSet()).hasSize(4)
        refs.forEach { assertThat(it).matches("inst-[0-9a-f]{32}") }
        assertThat(InstallationGrantRef.of("a", "b".repeat(300))).hasSize(37)
        // the pinned form: SHA-256 over role, NUL and subject
        assertThat(InstallationGrantRef.of("platform.engineer", "user:eng-1"))
            .isEqualTo(
                "inst-" +
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest("platform.engineer\u0000user:eng-1".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                        .take(32),
            )
    }

    @Test
    fun `INV-AC-9212 every event has the platform class, tenant, entity and key and the platform JSON form`() {
        reset()
        var appended = 0
        val events =
            start(op1, eng1) { context ->
                appended = context.getBean(TransactionRunner::class.java).let { outboxRows() }
            }
        assertThat(events).hasSize(2)
        assertThat(appended).describedAs("the real outbox holds the platform rows").isEqualTo(2)
        val wire = EnvelopeJson(json)
        events.forEach {
            assertThat(it.type).isEqualTo(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType)
            assertThat(it.tenant).isEqualTo(TenantId.PLATFORM)
            assertThat(it.entity).isEqualTo(EntityRef.PLATFORM)
            assertThat(it.subject).isEqualTo(record(it)["principalRef"].asString())
            assertThat(it.docUuid).isNull()
            assertThat(it.transactionDate).isNull()
            val text = wire.write(it)
            val node = json.readTree(text)
            assertThat(node.has("tenantid")).isFalse()
            assertThat(node.has("entityref")).isFalse()
            val read = wire.read(text)
            assertThat(read.tenant).isEqualTo(TenantId.PLATFORM)
            assertThat(read.entity).isEqualTo(EntityRef.PLATFORM)
        }
    }

    private fun outboxRows(): Int =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from access.outbox where tenant_id = '${TenantId.PLATFORM}' " +
                            "and type = '$type'",
                    ).use {
                        it.next()
                        it.getInt(1)
                    }
            }
        }

    @Test
    fun `a tenant-bound transaction sees no installation role grant and cannot write one`() {
        reset()
        start(op1) { context ->
            val tx = context.getBean(TransactionRunner::class.java)
            val tenants = context.getBean(com.dynokore.platform.tenancykernel.TenantContext::class.java)
            val dsl = context.getBean(org.jooq.DSLContext::class.java)
            val seen =
                tenants.bind(TenantId(UUID.randomUUID()), "svc:probe") {
                    tx.inTransaction { dsl.fetchCount(INSTALLATION_ROLE_GRANT) }
                }
            assertThat(seen).isZero()
            val refused =
                runCatching {
                    tenants.bind(TenantId(UUID.randomUUID()), "svc:probe") {
                        tx.inTransaction {
                            dsl
                                .insertInto(INSTALLATION_ROLE_GRANT)
                                .set(INSTALLATION_ROLE_GRANT.GRANT_REF, "x")
                                .set(INSTALLATION_ROLE_GRANT.SUBJECT, "user:x")
                                .set(INSTALLATION_ROLE_GRANT.ROLE, "platform.engineer")
                                .set(INSTALLATION_ROLE_GRANT.STATE, "ACTIVE")
                                .set(INSTALLATION_ROLE_GRANT.AUTHORITY_VERSION, 1L)
                                .set(INSTALLATION_ROLE_GRANT.UPDATED_AT, java.time.OffsetDateTime.now())
                                .execute()
                        }
                    }
                }
            assertThat(refused.isFailure).isTrue()
            val platform = context.getBean(PlatformTenantBinding::class.java)
            val held =
                platform.bindPlatform("svc:probe") {
                    tx.inTransaction { dsl.fetchCount(INSTALLATION_ROLE_GRANT) }
                }
            assertThat(held).isEqualTo(1)
        }
    }

    /** The smallest permission key the generated map lists for [role] under the form of [context], never none. */
    private fun keyFor(
        context: AssertableWebApplicationContext,
        role: String,
    ): String {
        val map = context.getBean(OperationRoleMap::class.java)
        val matrix = context.getBean(PermissionMatrix::class.java)
        val candidates =
            matrix
                .export()
                .values
                .flatMap { it.keys }
                .toSortedSet()
        val key = candidates.firstOrNull { role in map.rolesFor(it) }
        return checkNotNull(key) { "the generated OperationRoleMap lists no permission key for $role" }
    }

    private fun replicaOf(context: AssertableWebApplicationContext): AuthorityReplica =
        AuthorityReplica(
            context.getBean(java.time.Clock::class.java),
            context.getBean(OperationRoleMap::class.java),
            fallback = null,
        ).also { it.onSnapshotComplete(0) }

    private fun decide(
        replica: AuthorityReplica,
        context: AssertableWebApplicationContext,
        subject: String,
        key: String,
    ): AuthorityDecision =
        replica.decide(
            AuthorizationRequest(
                TenantId.PLATFORM,
                Principal(subject, PrincipalKind.HUMAN),
                "",
                key,
                level = ScopeLevel.INSTALLATION,
            ),
            Deadline.after(Duration.ofMillis(500), context.getBean(java.time.Clock::class.java)),
        )

    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    private fun deliver(
        replica: AuthorityReplica,
        events: List<Envelope>,
    ) {
        val feed = AuthorityFeedHandler(replica)
        events.forEach { feed.handle(it) }
    }

    private fun envelopeOf(state: ReplayedState) =
        Envelope(
            id =
                com.dynokore.platform.outboxandqueue
                    .EventId(UUID.randomUUID().toString()),
            source = "/gst/access-svc",
            type = state.type,
            subject = state.aggregateKey,
            time = Instant.parse("2026-11-09T00:00:00Z"),
            tenant = TenantId.PLATFORM,
            entity = state.entity,
            docUuid = null,
            transactionDate = null,
            docSequence = state.docSequence,
            instanceId = "replay",
            correlation =
                com.dynokore.platform.outboxandqueue
                    .CorrelationId(UUID.randomUUID().toString()),
            causation =
                com.dynokore.platform.outboxandqueue
                    .CausationId(UUID.randomUUID().toString()),
            dataJson = state.dataJson,
        )

    private fun replayOf(context: AssertableWebApplicationContext): List<ReplayedState> {
        val source = context.getBean(ReplayStateSource::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val platform = context.getBean(PlatformTenantBinding::class.java)
        assertThat(platform.bindPlatform("svc:access-svc/job:replay") { source.tenants() }).contains(TenantId.PLATFORM)
        val all = mutableListOf<ReplayedState>()
        var cursor: String? = null
        do {
            val page =
                platform.bindPlatform("svc:access-svc/job:replay") {
                    tx.inTransaction { source.page(null, cursor, 1) }
                }
            all += page.items
            cursor = page.next
        } while (cursor != null)
        return all
    }

    private fun roundTrip(
        form: String,
        listed: Pair<String, String>,
    ) {
        reset()
        val liveEvents = mutableListOf<Envelope>()
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                form = form,
                tokenProperties = TestSecrets.TOKEN_PROPERTIES + entries(listed, "user:other" to listed.second),
            ).run { context ->
                context.startupFailure?.let { throw it }
                liveEvents += context.getBean(CapturedEvents::class.java).envelopes.filter { it.type == type }
                val key = keyFor(context, listed.second)
                val live = replicaOf(context)
                deliver(live, liveEvents)
                val replayed = replicaOf(context)
                val states = replayOf(context)
                assertThat(states.map { it.entity }).containsOnly(EntityRef.PLATFORM)
                states.forEach { replayed.let { r -> AuthorityFeedHandler(r).handle(envelopeOf(it)) } }
                val subjects =
                    listOf(listed.first, "user:other", "user:unlisted", "${listed.first} ", listed.first.uppercase())
                val fromLive = subjects.map { decide(live, context, it, key) }
                val fromReplay = subjects.map { decide(replayed, context, it, key) }
                assertThat(fromLive).isEqualTo(listOf(granted, granted, noGrant, noGrant, noGrant))
                assertThat(fromReplay).isEqualTo(fromLive)
                assertThat(live.holdersAt(TenantId(UUID.randomUUID()), listed.second, ScopeLevel.INSTALLATION))
                    .containsExactlyInAnyOrder(listed.first, "user:other")
            }
        // a start without the second entry: only that entry gets a REMOVED event and its row stays held for the replay
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                form = form,
                tokenProperties = TestSecrets.TOKEN_PROPERTIES + entries(listed),
            ).run { context ->
                context.startupFailure?.let { throw it }
                val removal = context.getBean(CapturedEvents::class.java).envelopes.filter { it.type == type }
                assertThat(removal.map { state(it) }).containsExactly("REMOVED")
                val key = keyFor(context, listed.second)
                val live = replicaOf(context)
                deliver(live, liveEvents + removal)
                val replayed = replicaOf(context)
                replayOf(context).forEach { AuthorityFeedHandler(replayed).handle(envelopeOf(it)) }
                assertThat(replayOf(context).map { state(envelopeOf(it)) })
                    .containsExactlyInAnyOrder("ACTIVE", "REMOVED")
                listOf(listed.first, "user:other", "user:unlisted").let { subjects ->
                    val expected = listOf(granted, noGrant, noGrant)
                    assertThat(subjects.map { decide(live, context, it, key) }).isEqualTo(expected)
                    assertThat(subjects.map { decide(replayed, context, it, key) }).isEqualTo(expected)
                }
                assertThat(live.holdersAt(TenantId(UUID.randomUUID()), listed.second, ScopeLevel.INSTALLATION))
                    .containsExactly(listed.first)
            }
    }

    @Test
    fun `INV-AC-9212 round trip in the private form the decisions of the live events and of the replay equal`() =
        roundTrip("PRIVATE", op1)

    @Test
    fun `INV-AC-9212 round trip in the SaaS form the decisions of the live events and of the replay equal`() =
        roundTrip("SAAS", eng1)
}
