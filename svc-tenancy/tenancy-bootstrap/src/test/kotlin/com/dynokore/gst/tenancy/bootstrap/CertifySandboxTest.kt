package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.certification.ConsumedCertificationEvents
import com.dynokore.gst.tenancy.adapters.events.CertificationStatusChangedOutbox
import com.dynokore.gst.tenancy.adapters.events.PublishedChannels
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.application.certification.CertifyOutcome
import com.dynokore.gst.tenancy.application.installation.InstallationStep
import com.dynokore.gst.tenancy.application.installation.RecordInstallationAction
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private typealias CertOutcome = CertificationOutcome

private const val PACK_CONSUMER = "certificationRulePackActivatedConsumer"
private const val TRIPLE_CONSUMER = "certificationIdentityTripleChangedConsumer"
private val APPLIED_ONE = PollResult(1, 0, 0, 0, 0)
private const val CALENDAR_PACK =
    "{\"packIdentifier\":\"CAL-SG\",\"version\":\"1.0\",\"effectiveFrom\":\"2026-11-01\",\"packType\":\"CALENDAR\"}"

/**
 * certifySandbox at the boundary of the service (INV-T-16409): the controller, the store and the wired event consumers
 * over PostgreSQL, against the contract stubs. Events are delivered through the real consumer beans.
 */
class CertifySandboxTest {
    private fun run(block: (CertificationEnv, ProfileEnv) -> Unit) {
        val log = TopicLog()
        BoundaryFixture
            .runner()
            .withBean(com.dynokore.platform.outboxandqueue.EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                block(CertificationEnv(env, log), env)
            }
    }

    // ---- Subject A: the response and the stored row ----------------------------------------------------------------

    @Test
    fun `certifySandbox records the result and the stored row equals the request (use-case seam)`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CERTIFIER, entity, RECORD_KEY)
                val passed = (e.certify(entity, CertOutcome.PASSED, suite = "2026.1") as CertifyOutcome.Recorded).record
                assertThat(passed.entityRef).isEqualTo(entity.entityRef)
                assertThat(passed.outcome).isEqualTo(CertificationOutcome.PASSED)
                assertThat(passed.failedScenarios).isNull()
                val scenarios = listOf("SC-1", "SC-2")
                val failed =
                    (
                        e.certify(
                            entity,
                            CertOutcome.FAILED,
                            scenarios,
                            suite = "2026.2",
                        ) as CertifyOutcome.Recorded
                    ).record
                assertThat(failed.failedScenarios).containsExactly("SC-1", "SC-2")
                val rows = e.rows(entity).map { e.payload(it) }
                assertThat(rows).hasSize(2)
                assertThat(rows[0]["outcome"].asString()).isEqualTo("PASSED")
                assertThat(rows[0]["suite_version"].asString()).isEqualTo("2026.1")
                assertThat(rows[0]["failed_scenarios"].isNull).isTrue()
                assertThat(rows[0]["recorded_by"].asString()).isEqualTo(CERTIFIER)
                assertThat(Instant.parse(rows[0]["recorded_at"].asString().replace("+00:00", "Z")))
                    .isEqualTo(passed.recordedAt)
                assertThat(rows[1]["outcome"].asString()).isEqualTo("FAILED")
                assertThat(rows[1]["suite_version"].asString()).isEqualTo("2026.2")
                assertThat(rows[1]["failed_scenarios"].values().map { it.asString() }).containsExactly("SC-1", "SC-2")
                assertThat(Instant.parse(rows[1]["recorded_at"].asString().replace("+00:00", "Z")))
                    .isEqualTo(failed.recordedAt)
                assertThat(rows.size).isEqualTo(2)
            }
        }
    }

    @Test
    fun `probe a caller with only entity-config manage is Forbidden (403 at the boundary) and no row is stored`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CONFIG_ONLY, entity, "entity-config.manage")
                assertThat(e.certify(entity)).isEqualTo(CertifyOutcome.Forbidden)
                assertThat(e.rows(entity)).isEmpty()
                assertThat(e.state(entity)).isNull()
            }
        }
    }

    @Test
    fun `probe a caller with the key sandbox-certification record records (201 at the boundary)`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CERTIFIER, entity, RECORD_KEY)
                assertThat(e.certify(entity)).isInstanceOf(CertifyOutcome.Recorded::class.java)
                assertThat(e.rows(entity)).hasSize(1)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
            }
        }
    }

    @Test
    fun `probe FAILED without failedScenarios is Invalid (400 at the boundary) and no row is stored`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CERTIFIER, entity, RECORD_KEY)
                val invalid = CertifyOutcome.Invalid::class.java
                assertThat(e.certify(entity, CertOutcome.FAILED, null)).isInstanceOf(invalid)
                assertThat(e.certify(entity, CertOutcome.FAILED, emptyList())).isInstanceOf(invalid)
                assertThat(e.certify(entity, CertOutcome.PASSED, listOf("SC-1"))).isInstanceOf(invalid)
                assertThat(e.rows(entity)).isEmpty()
            }
        }
    }

    // ---- Subject B: NEW_PACK and ISSUANCE_MODE ---------------------------------------------------------------------

    @Test
    fun `probe the rule-pack-activated event delivered twice leaves the state stale with one change`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.rulePackActivated(tenant)
                assertThat(e.poll(PACK_CONSUMER)).isEqualTo(APPLIED_ONE)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_PACK")
                e.rulePackActivated(tenant)
                e.poll(PACK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_PACK")
                assertThat(e.stored(entity)).hasSize(1)
            }
        }
    }

    @Test
    fun `probe the rule-pack-activated event for an entity without a record marks nothing`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.rulePackActivated(tenant)
                e.poll(PACK_CONSUMER)
                assertThat(e.state(entity)).isNull()
                assertThat(e.stored(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe an event of a look-alike rule-pack topic is ignored`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.rulePackActivated(tenant, topic = "gst.rules.rule-pack-activated.v2")
                e.rulePackActivated(tenant, topic = "gst.rules.rule-pack-activated.v1 ")
                e.poll(PACK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                assertThat(e.stored(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe a rule-pack-activated event of a pack of another kind marks as NEW_PACK like a regulatory pack`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.rulePackActivated(
                    tenant,
                    data = CALENDAR_PACK,
                )
                e.poll(PACK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_PACK")
            }
        }
    }

    @Test
    fun `an issuance-mode election commits and marks the certification stale in its transaction`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.passed(entity)
                ElectionEnv(env).usualPeople(entity)
                ElectionEnv(env).mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                assertThat(e.state(entity)).isEqualTo("STALE" to "ISSUANCE_MODE")
                val event = e.payload(e.stored(entity).single())
                assertThat(event["trigger"].asString()).isEqualTo("ISSUANCE_MODE")
            }
        }
    }

    @Test
    fun `probe an issuance-mode election that rolls back leaves the state unchanged and no event`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.passed(entity)
                val election = ElectionEnv(env)
                election.usualPeople(entity)
                runCatching {
                    e.tx.inTransaction {
                        election.mode(entity, SetIssuanceModeRequest.Mode.RELAY)
                        error("roll back the election")
                    }
                }
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                assertThat(e.stored(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe a CertificationStatusChanged whose transaction rolls back leaves no event`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.identityTripleChanged(tenant, entity.entityRef)
                e.poll(TRIPLE_CONSUMER)
                assertThat(e.stored(entity)).hasSize(1)
                e.actAs(CERTIFIER, entity, RECORD_KEY)
                runCatching {
                    e.tx.inTransaction {
                        e.certify(entity) // STALE to CURRENT writes the second event
                        error("roll back the certification")
                    }
                }
                assertThat(e.state(entity)).isEqualTo("STALE" to "IDENTITY_TRIPLE")
                assertThat(e.stored(entity)).hasSize(1)
                assertThat(e.rows(entity)).hasSize(1)
            }
        }
    }

    // ---- Subject C: NEW_TRAIN, SCHEME_HANDLER, IDENTITY_TRIPLE ------------------------------------------------------

    @Test
    fun `INV-AC-1148 a changed releaseTrain marks the entity with a PASSED record stale and writes NEW_TRAIN`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.passed(entity)
                val recorder = env.context.getBean(RecordInstallationAction::class.java)
                val run = UUID.randomUUID()
                val t0 = Instant.now() // the fact table is shared by the tests: times only grow

                fun step(
                    n: Int,
                    train: String,
                    at: Instant,
                ) = InstallationStep(
                    run,
                    n,
                    "UPGRADE",
                    "s$n",
                    "SUCCEEDED",
                    at,
                    "r",
                    "op",
                    null,
                    null,
                    null,
                    mapOf("releaseTrain" to train),
                )
                recorder.handle(step(1, "2026.1", t0))
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                val before = e.markRequestedIds()
                recorder.handle(step(2, "2026.2", t0.plusSeconds(60)))
                // the mark runs on delivery of the event written in the transaction of the fact (INV-Q-18156)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                val written = e.markRequestedIds() - before.toSet()
                assertThat(written).hasSize(1)
                e.deliverMarkRequested(written.single())
                assertThat(e.poll(MARK_CONSUMER)).isEqualTo(APPLIED_ONE)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_TRAIN")
                val event = e.payload(e.stored(entity).single())
                assertThat(event["trigger"].asString()).isEqualTo("NEW_TRAIN")
                assertThat(event["status"].asString()).isEqualTo("STALE")
                // the same fact again changes nothing and writes no second event
                recorder.handle(step(3, "2026.2", t0.plusSeconds(120)))
                assertThat(e.stored(entity)).hasSize(1)
            }
        }
    }

    @Test
    fun `probe IdentityTripleChanged delivered twice leaves the state stale with one change and one event`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.identityTripleChanged(tenant, entity.entityRef)
                assertThat(e.poll(TRIPLE_CONSUMER)).isEqualTo(APPLIED_ONE)
                e.identityTripleChanged(tenant, entity.entityRef)
                e.poll(TRIPLE_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("STALE" to "IDENTITY_TRIPLE")
                assertThat(e.stored(entity)).hasSize(1)
                assertThat(e.aggregateKeys(entity)).containsExactly(entity.entityRef)
            }
        }
    }

    @Test
    fun `probe IdentityTripleChanged for an entity without a record marks nothing and writes no event`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.identityTripleChanged(tenant, entity.entityRef)
                e.poll(TRIPLE_CONSUMER)
                assertThat(e.state(entity)).isNull()
                assertThat(e.stored(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe an event of the look-alike topic identity-triple-corrected is ignored`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.identityTripleChanged(tenant, entity.entityRef, topic = "gst.masterdata.identity-triple-corrected.v1")
                e.poll(TRIPLE_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                assertThat(e.stored(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `a new PASSED record of a stale certification makes it CURRENT and publishes one event`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.identityTripleChanged(tenant, entity.entityRef)
                e.poll(TRIPLE_CONSUMER)
                e.certify(entity)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                val events = e.stored(entity).map { e.payload(it) }
                assertThat(events.map { it["status"].asString() }).containsExactlyInAnyOrder("STALE", "CURRENT")
                assertThat(events.single { it["status"].asString() == "CURRENT" }.has("trigger")).isFalse()
            }
        }
    }

    // ---- the wiring -------------------------------------------------------------------------------------------

    @Test
    fun `the subscriptions this task adds equal the rule-pack, identity-triple and mark-requested topics`() {
        run { e, env ->
            assertThat(ConsumedCertificationEvents.TOPICS)
                .isEqualTo(
                    setOf(
                        "gst.rules.rule-pack-activated.v1",
                        "gst.masterdata.identity-triple-changed.v1",
                        "gst.tenancy.certification-mark-requested.v1",
                    ),
                )
            val catalogue = TenancyEventCatalogue.load()
            val topics = catalogue.entries().filter { it.eventType == TRIPLE_TYPE }.map { it.topic }
            assertThat(topics).containsExactly(TRIPLE_TOPIC)
            val consumers = env.context.getBeansOfType(InboxConsumer::class.java).keys
            assertThat(consumers).contains(PACK_CONSUMER, TRIPLE_CONSUMER, MARK_CONSUMER)
            assertThat(e.poll(PACK_CONSUMER)).isNotNull
        }
    }

    @Test
    fun `the published channel set gains exactly CertificationStatusChanged`() {
        assertThat(PublishedChannels.ALL).contains(CertificationStatusChangedOutbox.CHANNEL)
        assertThat(PublishedChannels.ALL - setOf("CertificationStatusChanged")).containsExactlyInAnyOrder(
            "OrganisationStructureChanged",
            "EntityProfileChanged",
            "FilingFrequencyChanged",
            "PeriodExtended",
            "PeriodDueDateChanged",
        )
    }
}
