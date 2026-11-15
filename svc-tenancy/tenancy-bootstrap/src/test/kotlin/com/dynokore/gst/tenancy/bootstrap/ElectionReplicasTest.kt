package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackActivatedHandler
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackDecoder
import com.dynokore.gst.tenancy.adapters.calendar.CalendarPackStagedHandler
import com.dynokore.gst.tenancy.adapters.certification.ConsumedCertificationEvents
import com.dynokore.gst.tenancy.adapters.certification.RulePackActivatedCertificationHandler
import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.gst.tenancy.adapters.election.RulePackActivatedHandler
import com.dynokore.gst.tenancy.adapters.election.RulePackStagedHandler
import com.dynokore.gst.tenancy.adapters.election.UnrecordedEntityFacts
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.application.certification.CertifiedEntityDirectory
import com.dynokore.gst.tenancy.application.certification.MarkCertificationStale
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The route replica and the scope pack replica, fed through the handlers of the inbox consumers. */
class ElectionReplicasTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private fun observeRefusal(
        e: ElectionEnv,
        entity: com.dynokore.gst.tenancy.contract.model.EntityResponse,
    ) = e.reason { e.mode(entity, SetIssuanceModeRequest.Mode.OBSERVE) }

    // ---- the route replica: the six probes -------------------------------------------------------------------

    @Test
    fun `route probe an entity listed on no row is refused naming the entity`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf("ENT-OTHER"), "POLLING", 1)
                assertThat(observeRefusal(e, entity)).contains(entity.entityRef)
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE)).isEmpty()
            }
        }
    }

    @Test
    fun `route probe a row without a route is refused naming the connection`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-9", listOf(entity.entityRef), null, 1)
                assertThat(observeRefusal(e, entity)).contains("AP-9")
            }
        }
    }

    @Test
    fun `route probe a row cleared by a later event refuses OBSERVE`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "REPORT_IMPORT", 1)
                assertThat(e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), null, 2)).isTrue()
                assertThat(observeRefusal(e, entity)).contains("AP-1")
            }
        }
    }

    @Test
    fun `route probe an entity removed from the list by a later event refuses OBSERVE`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef, "ENT-2"), "AP_CALLBACK", 1)
                e.routeChanged(tenant, "AP-1", listOf("ENT-2"), "AP_CALLBACK", 2)
                assertThat(observeRefusal(e, entity)).contains(entity.entityRef)
            }
        }
    }

    @Test
    fun `route probe a row of another tenant's connection with the same id is not read`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                var other: TenantId? = null
                env.withEntity { second, _ ->
                    other = second
                    e.routeChanged(second, "AP-1", listOf(entity.entityRef), "AP_CALLBACK", 5)
                }
                assertThat(other).isNotEqualTo(tenant)
                assertThat(observeRefusal(e, entity)).contains(entity.entityRef).contains("no Access Point connection")
                // the same connection id in this tenant is its own row
                assertThat(e.routeChanged(tenant, "AP-1", listOf("ENT-OTHER"), null, 1)).isTrue()
            }
        }
    }

    @Test
    fun `route probe an elected row that lists the entity is accepted`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "AP_CALLBACK", 1)
                assertThat(observeRefusal(e, entity)).isNull()
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE).single().value).isEqualTo("OBSERVE")
            }
        }
    }

    @Test
    fun `a redelivery and an older event after a newer one leave the route row unchanged`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                assertThat(e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "POLLING", 3)).isTrue()
                // the same event again, and an older one that would clear the route
                assertThat(e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "POLLING", 3)).isFalse()
                assertThat(e.routeChanged(tenant, "AP-1", emptyList(), null, 2)).isFalse()
                val row =
                    env.inTenant(tenant) {
                        env.inTx { e.routes.lookup(entity.entityRef) }
                    }
                assertThat(row.toString()).contains("AP-1").contains("POLLING")
                // a higher version replaces the whole list
                assertThat(e.routeChanged(tenant, "AP-1", emptyList(), null, 4)).isTrue()
            }
        }
    }

    // ---- the scope pack replica ----------------------------------------------------------------------------------

    @Test
    fun `scope pack a listed source with a listed basis is accepted and another source's basis is refused`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.staged(
                    tenant,
                    "SCOPE-SG",
                    "1.0",
                    sources = TWO_SOURCES,
                )
                e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(1))
                // MAS lists DAILY only: BNM's basis is not MAS's
                assertThat(e.reason { e.rate(entity, "MAS", RateSourceElectionRequest.Basis.MONTHLY_AVERAGE) })
                    .contains("not on the approved list")
                assertThat(e.reason { e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY) }).isNull()
            }
        }
    }

    @Test
    fun `scope pack no scope pack seen and a staged but never activated version refuse every source`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                assertThat(e.reason { e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY) })
                    .contains("no scope pack is active")
                e.staged(tenant, "SCOPE-SG", "1.0")
                assertThat(e.reason { e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY) })
                    .contains("no scope pack is active")
                assertThat(e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE)).isEmpty()
            }
        }
    }

    @Test
    fun `scope pack a pack of another packType is ignored and the version active on the date decides`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                assertThat(e.staged(tenant, "CAL-SG", "1.0", packType = "CALENDAR")).isFalse()
                e.activated(tenant, "CAL-SG", "1.0", TODAY.minusDays(1))
                assertThat(e.reason { e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY) })
                    .contains("no scope pack is active")
                e.staged(tenant, "SCOPE-SG", "1.0")
                e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(10))
                e.staged(tenant, "SCOPE-SG", "2.0", sources = "[{\"sourceId\":\"BNM\",\"bases\":[\"DAILY\"]}]")
                e.activated(tenant, "SCOPE-SG", "2.0", TODAY.plusDays(20))
                // on the election's date version 1.0 is active: MAS is listed, BNM is not yet
                assertThat(e.reason { e.rate(entity, "BNM", RateSourceElectionRequest.Basis.DAILY) })
                    .contains("not on the approved list")
                assertThat(e.reason { e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY) }).isNull()
            }
        }
    }

    @Test
    fun `scope pack a redelivery and an older version leave the tables unchanged`() {
        run { e, env ->
            env.withEntity { tenant, _ ->
                assertThat(e.staged(tenant, "SCOPE-SG", "2.0")).isTrue()
                assertThat(e.staged(tenant, "SCOPE-SG", "2.0")).isFalse()
                assertThat(e.activated(tenant, "SCOPE-SG", "2.0", TODAY)).isTrue()
                assertThat(e.activated(tenant, "SCOPE-SG", "2.0", TODAY)).isFalse()
                // an older version activated with an earlier date after the newer one is ignored
                assertThat(e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))).isFalse()
                val sources = env.inTenant(tenant) { env.inTx { e.packs.approvedOn(TODAY) } }
                assertThat(sources!!.map { it.sourceId }).containsExactly("MAS")
                assertThat(env.inTenant(tenant) { env.inTx { e.packs.approvedOn(TODAY.minusDays(1)) } }).isNull()
            }
        }
    }

    // ---- RulePackStaged is accepted only as the platform type (INV-AC-22371) --------------------------------------

    private fun stagedHandlers(env: ProfileEnv): Map<String, EventHandler> {
        val mapper = JsonMapper.builder().build()
        return mapOf(
            "election" to RulePackStagedHandler(env.context.getBean(ScopePackStore::class.java), mapper),
            "calendar" to
                CalendarPackStagedHandler(
                    env.context.getBean(CalendarPackReplica::class.java),
                    mapper,
                    env.context.getBean(CalendarPackDecoder::class.java),
                ),
        )
    }

    private fun refusal(
        handler: EventHandler,
        type: String,
    ): String? =
        runCatching {
            handler.handle(testEnvelope(type, TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001")), "{}"))
        }.exceptionOrNull()
            ?.message

    private fun activatedHandlers(env: ProfileEnv): Map<String, EventHandler> {
        val mapper = JsonMapper.builder().build()
        val ctx = env.context
        return mapOf(
            "election" to RulePackActivatedHandler(ctx.getBean(ScopePackStore::class.java), mapper),
            "calendar" to CalendarPackActivatedHandler(ctx.getBean(CalendarPackReplica::class.java), mapper),
            "certification" to
                RulePackActivatedCertificationHandler(
                    ctx.getBean(CertifiedEntityDirectory::class.java),
                    ctx.getBean(TenantContext::class.java),
                    ctx.getBean(TransactionRunner::class.java),
                    ctx.getBean(MarkCertificationStale::class.java),
                    mapper,
                    "identity:USR-C1",
                ),
        )
    }

    @Test
    fun `INV-AC-23120 the activated constants and the subscriptions equal the platform names`() {
        assertThat(ConsumedElectionEvents.RULE_PACK_ACTIVATED)
            .isEqualTo("com.dynokore.gst.platform.RulePackActivated.v1")
        assertThat(ConsumedElectionEvents.RULE_PACK_ACTIVATED_TOPIC).isEqualTo("gst.platform.rule-pack-activated.v1")
        assertThat(CalendarPackActivatedHandler.ACTIVATED).isEqualTo("com.dynokore.gst.platform.RulePackActivated.v1")
        assertThat(ConsumedCertificationEvents.RULE_PACK_ACTIVATED)
            .isEqualTo("com.dynokore.gst.platform.RulePackActivated.v1")
        assertThat(ConsumedCertificationEvents.RULE_PACK_ACTIVATED_TOPIC)
            .isEqualTo("gst.platform.rule-pack-activated.v1")
        // the election map on main with the activated entry replaced, compared as a set of pairs
        assertThat(
            ConsumedElectionEvents.SUBSCRIPTIONS.entries
                .map { it.key to it.value }
                .toSet(),
        ).isEqualTo(
            setOf(
                "com.dynokore.gst.transport.AccessPointRouteChanged.v1" to
                    "gst.transport.access-point-route-changed.v1",
                "com.dynokore.gst.platform.RulePackStaged.v1" to "gst.platform.rule-pack-staged.v1",
                "com.dynokore.gst.platform.RulePackActivated.v1" to "gst.platform.rule-pack-activated.v1",
            ),
        )
        assertThat(
            ConsumedElectionEvents.SUBSCRIPTIONS,
        ).doesNotContainKey("com.dynokore.gst.rules.RulePackActivated.v1")
        assertThat(ConsumedElectionEvents.SUBSCRIPTIONS.values).doesNotContain("gst.rules.rule-pack-activated.v1")
        assertThat(ConsumedCertificationEvents.TOPICS)
            .isEqualTo(
                setOf(
                    "gst.platform.rule-pack-activated.v1",
                    "gst.masterdata.identity-triple-changed.v1",
                    "gst.platform.certification-mark-requested.v1",
                ),
            )
        assertThat(ConsumedCertificationEvents.TOPICS).doesNotContain("gst.rules.rule-pack-activated.v1")
    }

    @Test
    fun `INV-AC-23120 the old rules-namespace type is refused as unexpected by the three handlers`() {
        run { _, env ->
            activatedHandlers(env).forEach { (name, handler) ->
                assertThat(refusal(handler, "com.dynokore.gst.rules.RulePackActivated.v1"))
                    .describedAs(name)
                    .isEqualTo("unexpected com.dynokore.gst.rules.RulePackActivated.v1")
            }
        }
    }

    @Test
    fun `INV-AC-23120 the look-alike platform type is refused as unexpected by the three handlers`() {
        run { _, env ->
            activatedHandlers(env).forEach { (name, handler) ->
                assertThat(refusal(handler, "com.dynokore.gst.platform.RulePackActivatedX.v1"))
                    .describedAs(name)
                    .isEqualTo("unexpected com.dynokore.gst.platform.RulePackActivatedX.v1")
            }
        }
    }

    @Test
    fun `INV-AC-23120 the platform type is not refused as unexpected by the three handlers`() {
        run { _, env ->
            activatedHandlers(env).forEach { (name, handler) ->
                assertThat(refusal(handler, "com.dynokore.gst.platform.RulePackActivated.v1") ?: "")
                    .describedAs(name)
                    .doesNotStartWith("unexpected ")
            }
        }
    }

    @Test
    fun `INV-AC-22371 the staged constants and the subscription equal the platform names`() {
        assertThat(ConsumedElectionEvents.RULE_PACK_STAGED).isEqualTo("com.dynokore.gst.platform.RulePackStaged.v1")
        assertThat(ConsumedElectionEvents.RULE_PACK_STAGED_TOPIC).isEqualTo("gst.platform.rule-pack-staged.v1")
        assertThat(CalendarPackStagedHandler.STAGED).isEqualTo("com.dynokore.gst.platform.RulePackStaged.v1")
        // the map on main with the staged entry replaced, compared as a set of pairs
        assertThat(
            ConsumedElectionEvents.SUBSCRIPTIONS.entries
                .map { it.key to it.value }
                .toSet(),
        ).isEqualTo(
            setOf(
                "com.dynokore.gst.transport.AccessPointRouteChanged.v1" to
                    "gst.transport.access-point-route-changed.v1",
                "com.dynokore.gst.platform.RulePackStaged.v1" to "gst.platform.rule-pack-staged.v1",
                "com.dynokore.gst.platform.RulePackActivated.v1" to "gst.platform.rule-pack-activated.v1",
            ),
        )
        assertThat(ConsumedElectionEvents.SUBSCRIPTIONS).doesNotContainKey("com.dynokore.gst.rules.RulePackStaged.v1")
        assertThat(ConsumedElectionEvents.SUBSCRIPTIONS.values).doesNotContain("gst.rules.rule-pack-staged.v1")
    }

    @Test
    fun `INV-AC-22371 the old rules-namespace type is refused as unexpected by both handlers`() {
        run { _, env ->
            stagedHandlers(env).forEach { (name, handler) ->
                assertThat(refusal(handler, "com.dynokore.gst.rules.RulePackStaged.v1"))
                    .describedAs(name)
                    .isEqualTo("unexpected com.dynokore.gst.rules.RulePackStaged.v1")
            }
        }
    }

    @Test
    fun `INV-AC-22371 the look-alike platform type is refused as unexpected by both handlers`() {
        run { _, env ->
            stagedHandlers(env).forEach { (name, handler) ->
                assertThat(refusal(handler, "com.dynokore.gst.platform.RulePackStagedX.v1"))
                    .describedAs(name)
                    .isEqualTo("unexpected com.dynokore.gst.platform.RulePackStagedX.v1")
            }
        }
    }

    @Test
    fun `INV-AC-22371 the platform type is handled by both handlers`() {
        run { _, env ->
            val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val data = "{\"packType\":\"other\"}"
            stagedHandlers(env).forEach { (name, handler) ->
                assertThat(
                    handler.handle(testEnvelope("com.dynokore.gst.platform.RulePackStaged.v1", tenant, data)).name,
                ).describedAs(name)
                    .isEqualTo("IGNORED")
            }
        }
    }

    // ---- the wiring ------------------------------------------------------------------------------------------------

    @Test
    fun `the consumer wiring is exactly the three subscriptions and the catalogue lists them`() {
        run { e, env ->
            assertThat(ConsumedElectionEvents.SUBSCRIPTIONS.keys)
                .containsExactlyInAnyOrder(
                    "com.dynokore.gst.transport.AccessPointRouteChanged.v1",
                    "com.dynokore.gst.platform.RulePackStaged.v1",
                    "com.dynokore.gst.platform.RulePackActivated.v1",
                )
            assertThat(ConsumedElectionEvents.SUBSCRIPTIONS.values)
                .containsExactlyInAnyOrder(
                    "gst.transport.access-point-route-changed.v1",
                    "gst.platform.rule-pack-staged.v1",
                    "gst.platform.rule-pack-activated.v1",
                )
            val catalogue = TenancyEventCatalogue.load()
            ConsumedElectionEvents.SUBSCRIPTIONS.forEach { (type, topic) ->
                assertThat(catalogue.entries().filter { it.eventType == type }.map { it.topic }).containsExactly(topic)
            }
            val consumers = env.context.getBeansOfType(InboxConsumer::class.java)
            assertThat(consumers.keys)
                .contains("accessPointRouteChangedConsumer", "rulePackStagedConsumer", "rulePackActivatedConsumer")
            assertThat(e.facts).isNotNull
        }
    }

    @Test
    fun `no synchronous edge from tenancy-svc to transport-svc exists in the service`() {
        val sources = Path.of("..").toAbsolutePath().normalize()
        val edge = Regex("tenancy-svc\\s*->\\s*transport-svc|TransportClient")
        val hits =
            Files.walk(sources).use { files ->
                files
                    .filter { Files.isRegularFile(it) && it.toString().contains("/src/main/") }
                    .filter { edge.containsMatchIn(Files.readString(it)) }
                    .toList()
            }
        assertThat(hits).isEmpty()
    }

    @Test
    fun `the default binding of the entity facts answers NOT_RECORDED for every fact`() {
        val facts = UnrecordedEntityFacts()
        assertThat(
            listOf(
                facts.recordsPurchases("ENT-1"),
                facts.hasForeignCurrencyDocuments("ENT-1"),
                facts.claimsInputTaxOnPostingBasis("ENT-1"),
            ),
        ).containsOnly(FactAnswer.NOT_RECORDED)
    }
}
