package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * The replay source of AuthorityChanged at the service boundary (INV-T-9061, INV-AC-9051): the real application context
 * over a real PostgreSQL; the live events are written by the service's controllers and the replayed states are
 * compared with them by equality of payload (authorityVersion included).
 */
class AuthorityReplayTest {
    private val failAfterPublish = AtomicBoolean(false)

    private fun run(block: (AuthorityReplayKit) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .withBean(
                "failAfterGrantPublish",
                BeanPostProcessor::class.java,
                {
                    object : BeanPostProcessor {
                        override fun postProcessAfterInitialization(
                            bean: Any,
                            beanName: String,
                        ): Any =
                            if (bean is AuthorityChangePublisher) {
                                object : AuthorityChangePublisher {
                                    override fun publish(changes: List<RoleGrantChange>) {
                                        bean.publish(changes)
                                        check(!failAfterPublish.get()) { "injected failure after the publish step" }
                                    }
                                }
                            } else {
                                bean
                            }
                    }
                },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(AuthorityReplayKit(PrincipalKit(context)))
            }
    }

    private fun keyed(states: List<ReplayedState>) = states.associateBy { AuthorityReplayKit.keyOf(it) }

    private fun payloadOf(state: ReplayedState) = REPLAY_JSON.readTree(state.dataJson)

    private fun sql(text: String) =
        BoundaryFixture.asSuperuser { it.createStatement().use { statement -> statement.execute(text) } }

    @Test
    fun `INV-AC-9051 a full sync replays exactly the current records at the version of the last live event`() =
        run { env ->
            val built = env.build()
            val live = env.lastLive()
            val replayed = keyed(env.replay())

            val liveKeys =
                setOf(
                    "ROLE:${built.g1}",
                    "ROLE:${built.g2}",
                    "ROLE:${built.g3}",
                    "CONSENT:CLI-A",
                    "PRINCIPAL:${built.principal}",
                )
            assertThat(live.keys).containsAll(liveKeys)
            // the live keys plus the rows written without an event (the administrator and seeds, stamped by the test)
            assertThat(replayed.keys).isEqualTo(liveKeys + env.heldGrantRefs().map { "ROLE:$it" })
            liveKeys.forEach { key ->
                val payload = payloadOf(replayed.getValue(key))
                assertThat(payload).describedAs(key).isEqualTo(live.getValue(key))
                val expectedVersion = live.getValue(key)["authorityVersion"]
                assertThat(payload["authorityVersion"]).describedAs(key).isEqualTo(expectedVersion)
            }
            // the revoked grant is a removal that is held (a REVOKED row is never deleted)
            assertThat(payloadOf(replayed.getValue("ROLE:${built.g2}"))["roleGrantRecord"]["state"].asString())
                .isEqualTo("REMOVED")
            assertThat(payloadOf(replayed.getValue("ROLE:${built.g1}"))["roleGrantRecord"]["state"].asString())
                .isEqualTo("ACTIVE")
        }

    @Test
    fun `INV-AC-9051 a revoked grant replays as a removal with its stored version`() =
        run { env ->
            val built = env.build()
            val removal = env.lastLive().getValue("ROLE:${built.g2}")
            val stored = env.storedVersion("role_grant", "grant_ref", built.g2)
            val replayed = REPLAY_JSON.readTree(keyed(env.replay()).getValue("ROLE:${built.g2}").dataJson)
            assertThat(replayed["roleGrantRecord"]["state"].asString()).isEqualTo("REMOVED")
            assertThat(replayed["authorityVersion"].asLong()).isEqualTo(stored)
            assertThat(replayed["authorityVersion"].asLong()).isEqualTo(removal["authorityVersion"].asLong())
        }

    @Test
    fun `INV-AC-9051 a replay by TENANT scope lists no record of another tenant`() =
        run { first ->
            val second = AuthorityReplayKit(PrincipalKit(first.context))
            // look-alike: the same identity ref holds a grant in both tenants
            val a = first.grant("user:same", Role.AUDITOR, "tenant:${first.tenant}")
            val b = second.grant("user:same", Role.AUDITOR, "tenant:${second.tenant}")
            val ofFirst = first.replay(ReplayScope.Tenant(first.tenant))
            val keys = ofFirst.map { AuthorityReplayKit.keyOf(it) }
            assertThat(keys).contains("ROLE:${a.grantRef}").doesNotContain("ROLE:${b.grantRef}")
            assertThat(
                ofFirst
                    .map { REPLAY_JSON.readTree(it.dataJson) }
                    .filter { it.has("roleGrantRecord") }
                    .map {
                        it["roleGrantRecord"]["scopeRef"].asString()
                    }.toSet()
                    .minus(setOf("CLI-A", "CLI-B")),
            ).containsOnly(first.tenant.toString())
        }

    @Test
    fun `INV-AC-9051 a page of the maximum size is followed by the next page without duplicates or gaps`() =
        run { env ->
            val max = 5000
            val total = max + 1
            val tenant = env.tenant.value
            sql(
                "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                    "updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', 'user:bulk', " +
                    "'STAFF', now(), 'seed', now(), 'seed', 0)",
            )
            sql(
                "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                    "scope_ref, valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, " +
                    "version, authority_version) select gen_random_uuid(), '$tenant', " +
                    "'BULK-' || lpad(i::text, 6, '0'), 'user:bulk', '${Role.AUDITOR.wire}', 'TENANT', 'S' || i, " +
                    "now(), 'ACTIVE', 'BY_HAND', now(), 'seed', now(), 'seed', 0, " +
                    "nextval('access.authority_version_seq') from generate_series(1, $total) i",
            )
            val first = env.page(null, null, max)
            assertThat(first.items).hasSize(max)
            assertThat(first.next).isNotNull()
            val second = env.page(null, first.next, max)
            assertThat(second.next).isNull()
            val keys = (first.items + second.items).map { AuthorityReplayKit.keyOf(it) }
            assertThat(keys).doesNotHaveDuplicates()
            val bulk = keys.filter { it.startsWith("ROLE:BULK-") }
            assertThat(bulk).isEqualTo((1..total).map { "ROLE:BULK-" + it.toString().padStart(6, '0') }.sorted())
            val others = env.heldGrantRefs().filterNot { it.startsWith("BULK-") }.map { "ROLE:$it" }
            assertThat(keys.toSet()).isEqualTo(bulk.toSet() + others)
        }

    @Test
    fun `INV-AC-9051 replayed into a fresh AuthorityReplica the decisions equal those of the live feed`() =
        run { env ->
            val built = env.build()
            val live = replicaOf(env)
            val replayedInto = replicaOf(env)
            val handlerLive = AuthorityFeedHandler(live)
            val handlerReplay = AuthorityFeedHandler(replayedInto)
            env.liveEnvelopes().forEach { handlerLive.handle(it) }
            env.replay().forEach { handlerReplay.handle(envelopeOf(env.tenant, it)) }

            val requests =
                listOf("user:a", "user:b", "user:c").flatMap { who ->
                    listOf("CLI-A", "CLI-B").flatMap { entity ->
                        listOf("mapping.approve", "profile.edit", "doc.view").map { Triple(who, entity, it) }
                    }
                } + listOf(Triple(built.principal, "CLI-A", "doc.view"), Triple(built.principal, "CLI-B", "doc.view"))
            assertThat(requests).hasSize(20)
            val fromLive = requests.map { decide(env, live, it) }
            val fromReplay = requests.map { decide(env, replayedInto, it) }
            assertThat(fromReplay).isEqualTo(fromLive)
            // the state is not trivially all-deny: the live replica allows at least one request and denies at least one
            assertThat(fromLive.map { it.allowed }).contains(true, false)
        }

    @Test
    fun `INV-AC-9051 a live change stores the drawn version with the row`() =
        run { env ->
            val built = env.build()
            val live = env.lastLive()

            fun version(key: String) = live.getValue(key)["authorityVersion"].asLong()
            listOf(built.g1, built.g2, built.g3).forEach {
                val stored = env.storedVersion("role_grant", "grant_ref", it)
                assertThat(stored).describedAs(it).isEqualTo(version("ROLE:$it"))
            }
            assertThat(env.storedVersion("consent", "consent_ref", built.consent)).isEqualTo(version("CONSENT:CLI-A"))
            assertThat(env.storedVersion("service_principal", "principal_ref", built.principal))
                .isEqualTo(version("PRINCIPAL:${built.principal}"))
            // a consent withdrawal draws a higher version and stores it in the same row
            val before = version("CONSENT:CLI-A")
            env.withdraw(built.consent)
            val after = env.lastLive().getValue("CONSENT:CLI-A")["authorityVersion"].asLong()
            assertThat(after).isGreaterThan(before)
            assertThat(env.storedVersion("consent", "consent_ref", built.consent)).isEqualTo(after)
        }

    @Test
    fun `INV-AC-9051 a rolled-back change leaves no version and no event`() =
        run { env ->
            val built = env.build()
            val rowBefore = env.rowText("role_grant", "grant_ref", built.g1)
            val outboxBefore = env.outboxRows()
            val liveBefore = env.liveEnvelopes().size
            failAfterPublish.set(true)
            try {
                assertThatThrownBy { env.revokeGrant(built.g1) }.hasMessageContaining("injected failure")
            } finally {
                failAfterPublish.set(false)
            }
            assertThat(env.rowText("role_grant", "grant_ref", built.g1)).isEqualTo(rowBefore)
            assertThat(env.outboxRows()).isEqualTo(outboxBefore)
            // the capture sits above the database: it saw the append; the database kept nothing of it
            assertThat(env.liveEnvelopes().size).isGreaterThan(liveBefore)
        }

    @Test
    fun `INV-AC-9051 a pending grant is not replayed`() =
        run { env ->
            // the first-administrator invitation leaves a PENDING grant; the seeds cannot make one: insert the row
            val tenant = env.tenant.value
            val ref = "GRT-PENDING-${tenant.toString().take(8)}"
            sql(
                "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                    "updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', 'user:pending', " +
                    "'EXTERNAL', now(), 's', now(), 's', 0)",
            )
            sql(
                "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                    "scope_ref, valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, " +
                    "version) values (gen_random_uuid(), '$tenant', '$ref', 'user:pending', " +
                    "'${Role.TENANT_ADMIN.wire}', 'TENANT', '$tenant', now(), 'PENDING', 'BY_HAND', now(), 's', " +
                    "now(), 's', 0)",
            )
            assertThat(env.storedVersion("role_grant", "grant_ref", ref)).isNull()
            assertThat(env.replay().map { AuthorityReplayKit.keyOf(it) }).doesNotContain("ROLE:$ref")
        }

    @Test
    fun `INV-AC-9051 a withdrawn consent replays as WITHDRAWN when the client entity has no live consent`() =
        run { env ->
            env.kit.entity("CLI-A")
            val first = env.consent("CLI-A")
            env.withdraw(first.consentRef)
            val states = env.replay().map { REPLAY_JSON.readTree(it.dataJson) }.filter { it.has("consentRecord") }
            assertThat(states).hasSize(1)
            assertThat(states.single()["consentRecord"]["state"].asString()).isEqualTo("WITHDRAWN")
            // with a live consent for the same client entity, only the LIVE one is replayed
            env.consent("CLI-A")
            val after = env.replay().map { REPLAY_JSON.readTree(it.dataJson) }.filter { it.has("consentRecord") }
            assertThat(after).hasSize(1)
            assertThat(after.single()["consentRecord"]["state"].asString()).isEqualTo("LIVE")
            assertThat(after.single()).isEqualTo(env.lastLive().getValue("CONSENT:CLI-A"))
        }

    @Test
    fun `INV-AC-9051 a replayed state has no docsequence, like the live envelope`() =
        run { env ->
            env.build()
            assertThat(env.replay()).isNotEmpty().allSatisfy { assertThat(it.docSequence).isNull() }
            assertThat(env.liveEnvelopes()).isNotEmpty().allSatisfy { assertThat(it.docSequence).isNull() }
            assertThat(env.replay().map { it.entity }.toSet()).isEqualTo(env.liveEnvelopes().map { it.entity }.toSet())
        }

    @Test
    fun `INV-AC-9051 a replayed row without a stored version fails the page and writes nothing`() =
        run { env ->
            val ref = Seeds.grant(env.context, env.tenant, SeedSpec("user:unversioned", Role.AUDITOR))
            assertThat(env.storedVersion("role_grant", "grant_ref", ref)).isNull()
            val before = env.outboxRows()
            assertThatThrownBy { env.replay() }.hasMessageContaining(ref)
            assertThat(env.outboxRows()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9051 the ENTITY scope replays that entity and the PERIOD scope the records changed in it`() =
        run { env ->
            val t0 = env.kit.clock.freeze()
            env.kit.entity("CLI-A")
            val g1 = env.grant("user:a", Role.TAX_APPROVER, "entity:CLI-A")
            val g2 = env.grant("user:b", Role.ENTITY_ADMIN, "entity:CLI-B")
            env.consent("CLI-A")
            env.kit.clock.advance(Duration.ofHours(1))
            val g3 = env.grant("user:c", Role.AUDITOR, "tenant:${env.tenant}")

            fun keys(scope: ReplayScope) = env.replay(scope).map { AuthorityReplayKit.keyOf(it) }
            val g1Key = "ROLE:${g1.grantRef}"
            val g2Key = "ROLE:${g2.grantRef}"
            val g3Key = "ROLE:${g3.grantRef}"
            val ofEntity = keys(ReplayScope.Entity(env.tenant, EventEntityRef("CLI-A")))
            assertThat(ofEntity).contains(g1Key, "CONSENT:CLI-A").doesNotContain(g2Key, g3Key)

            val half = t0.plus(Duration.ofMinutes(30))
            val early = keys(ReplayScope.Period(t0.minus(Duration.ofHours(1)), half, env.tenant))
            assertThat(early).contains(g1Key, g2Key).doesNotContain(g3Key)
            val late = keys(ReplayScope.Period(half, t0.plus(Duration.ofHours(2)), env.tenant))
            assertThat(late).containsExactly(g3Key)
        }

    private fun decide(
        env: AuthorityReplayKit,
        replica: AuthorityReplica,
        request: Triple<String, String, String>,
    ): AuthorityDecision =
        replica.decide(
            AuthorizationRequest(
                env.tenant,
                Principal(request.first, PrincipalKind.HUMAN),
                request.second,
                request.third,
                null,
            ),
            Deadline.after(Duration.ofMillis(500), env.kit.clock),
        )

    private fun replicaOf(env: AuthorityReplayKit): AuthorityReplica {
        val structure = StructureReplica(env.kit.clock)
        listOf("CLI-A", "CLI-B").forEachIndexed { index, ref ->
            structure.accept(
                OrganisationStructureChanged(
                    env.tenant,
                    ref,
                    StructureChange.CREATED,
                    BoundaryFixture.entityNode(structureVersion = index + 1L),
                ),
            )
        }
        structure.onSnapshotComplete(2)
        val operations =
            mapOf(
                "mapping.approve" to mapOf(Role.TAX_APPROVER.wire to CellAllowance.PERMITTED),
                "profile.edit" to mapOf(Role.ENTITY_ADMIN.wire to CellAllowance.PERMITTED),
                "doc.view" to
                    listOf(Role.TAX_APPROVER, Role.ENTITY_ADMIN, Role.AUDITOR)
                        .associate { it.wire to CellAllowance.PERMITTED },
            )
        val roles = OperationRoleMap { operations[it].orEmpty() }
        return AuthorityReplica(env.kit.clock, roles, fallback = null, structure = structure)
            .also { it.onSnapshotComplete(0) }
    }

    /** The envelope a replayed state has once the library writes it under the bound tenant. */
    private fun envelopeOf(
        tenant: TenantId,
        state: ReplayedState,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/access-svc",
        type = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
        subject = state.aggregateKey,
        time = java.time.Instant.parse("2026-11-08T00:00:00Z"),
        tenant = tenant,
        entity = state.entity,
        docUuid = null,
        transactionDate = null,
        docSequence = state.docSequence,
        instanceId = "replay",
        correlation = CorrelationId(UUID.randomUUID().toString()),
        causation = CausationId(UUID.randomUUID().toString()),
        dataJson = state.dataJson,
    )
}
