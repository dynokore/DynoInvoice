package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.EventContexts
import com.dynokore.gst.tenancy.application.installation.InstallationStep
import com.dynokore.gst.tenancy.application.installation.RecordInstallationAction
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private const val PACK_CONSUMER = "certificationRulePackActivatedConsumer"
private const val FAIL_TRIGGER_ON_STATUS = "fail_certification_status_update"
private const val FAIL_TRIGGER_ON_OUTBOX = "fail_mark_requested_insert"
private const val RELEASE_TRAIN_SQL =
    "select fact_value from tenancy.installation_fact where fact_key = 'releaseTrain'"
private val APPLIED_ONE = PollResult(1, 0, 0, 0, 0)

/**
 * Atomic and retried stale marks (INV-Q-18156 B): the NEW_TRAIN mark is requested by an internal event written in the
 * transaction of the `releaseTrain` fact and carried out by an idempotent consumer. Failures are injected as database
 * triggers, so the real writers and consumers run unchanged.
 */
class CertificationMarkRetryTest {
    /** The wall clock plus an offset the tests advance past the inbox's retry backoff. */
    private class SkewClock : Clock() {
        @Volatile var skew: Duration = Duration.ZERO

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.now().plus(skew)
    }

    private val clock = SkewClock()

    /** The inbox schedules a failed event for later: move past the backoff so the next poll redelivers it. */
    private fun passBackoff() {
        clock.skew = clock.skew.plus(Duration.ofDays(1))
    }

    private fun run(block: (CertificationEnv, ProfileEnv) -> Unit) {
        val log = TopicLog()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .withBean(Clock::class.java, { clock })
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                block(CertificationEnv(env, log), env)
            }
    }

    private fun sql(statement: String) =
        BoundaryFixture.asSuperuser { c -> c.createStatement().use { it.execute(statement) } }

    private fun failFunction(
        name: String,
        schema: String = "tenancy",
    ) = sql(
        "create or replace function $schema.$name() returns trigger language plpgsql as " +
            "\$\$ begin raise exception 'injected failure'; end \$\$",
    )

    private fun dropInjection() {
        sql("drop trigger if exists $FAIL_TRIGGER_ON_STATUS on tenancy.certification_status")
        sql("drop trigger if exists $FAIL_TRIGGER_ON_OUTBOX on tenancy.outbox")
    }

    private fun step(
        run: UUID,
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

    // the fact table is shared by the tests, so the times only grow and the trains are unique per test
    private val t0 = Instant.now()
    private val train1 = "T1-${UUID.randomUUID()}"
    private val train2 = "T2-${UUID.randomUUID()}"

    /** A certified entity whose installation already holds a first train; returns the recorder and the run. */
    private fun withTrain(
        e: CertificationEnv,
        env: ProfileEnv,
        block: (RecordInstallationAction, UUID, com.dynokore.gst.tenancy.contract.model.EntityResponse) -> Unit,
    ) = env.withEntity { _, entity ->
        e.passed(entity)
        val recorder = env.context.getBean(RecordInstallationAction::class.java)
        val run = UUID.randomUUID()
        recorder.handle(step(run, 1, train1, t0))
        block(recorder, run, entity)
    }

    @Test
    fun `probe 1 the fact transaction rolls back, no event row and the entity stays CURRENT`() {
        run { e, env ->
            withTrain(e, env) { recorder, run, entity ->
                val before = e.markRequestedIds()
                runCatching {
                    e.tx.inTransaction {
                        recorder.handle(step(run, 2, train2, t0.plusSeconds(60)))
                        error("roll back the installation transaction")
                    }
                }
                assertThat(e.markRequestedIds()).isEqualTo(before)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
            }
        }
    }

    @Test
    fun `probe 2 the fact commits, the first mark fails, the redelivery ends STALE with one event`() {
        run { e, env ->
            withTrain(e, env) { recorder, run, entity ->
                val before = e.markRequestedIds()
                recorder.handle(step(run, 2, train2, t0.plusSeconds(60)))
                val id = (e.markRequestedIds() - before.toSet()).single()
                failFunction(FAIL_TRIGGER_ON_STATUS)
                sql(
                    "create trigger $FAIL_TRIGGER_ON_STATUS before update on tenancy.certification_status " +
                        "for each row execute function tenancy.$FAIL_TRIGGER_ON_STATUS()",
                )
                try {
                    e.deliverMarkRequested(id)
                    runCatching { e.poll(MARK_CONSUMER) }
                    assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                } finally {
                    dropInjection()
                }
                // the inbox redelivers the event that did not complete
                passBackoff()
                e.poll(MARK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_TRAIN")
                assertThat(e.stored(entity)).hasSize(1)
            }
        }
    }

    @Test
    fun `probe 3 the same event delivered twice makes one change and one CertificationStatusChanged`() {
        run { e, env ->
            withTrain(e, env) { recorder, run, entity ->
                val before = e.markRequestedIds()
                recorder.handle(step(run, 2, train2, t0.plusSeconds(60)))
                val id = (e.markRequestedIds() - before.toSet()).single()
                e.deliverMarkRequested(id)
                assertThat(e.poll(MARK_CONSUMER)).isEqualTo(APPLIED_ONE)
                e.deliverMarkRequested(id)
                e.poll(MARK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_TRAIN")
                assertThat(e.stored(entity)).hasSize(1)
            }
        }
    }

    @Test
    fun `probe 4 the rule-pack-activated handler whose first mark fails throws, and the redelivery ends STALE`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.passed(entity)
                e.rulePackActivated(tenant)
                failFunction(FAIL_TRIGGER_ON_STATUS)
                sql(
                    "create trigger $FAIL_TRIGGER_ON_STATUS before update on tenancy.certification_status " +
                        "for each row execute function tenancy.$FAIL_TRIGGER_ON_STATUS()",
                )
                try {
                    val thrown = runCatching { e.poll(PACK_CONSUMER) }.exceptionOrNull()
                    assertThat(thrown).isNotNull // the handler does not swallow the failure
                    assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                } finally {
                    dropInjection()
                }
                passBackoff()
                e.poll(PACK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("STALE" to "NEW_PACK")
                assertThat(e.stored(entity)).hasSize(1)
            }
        }
    }

    @Test
    fun `probe 5 an event of the look-alike topic certification-mark-request is ignored and marks nothing`() {
        run { e, env ->
            withTrain(e, env) { recorder, run, entity ->
                val before = e.markRequestedIds()
                recorder.handle(step(run, 2, train2, t0.plusSeconds(60)))
                val id = (e.markRequestedIds() - before.toSet()).single()
                e.deliverMarkRequested(id, topic = "gst.platform.certification-mark-request.v1")
                e.poll(MARK_CONSUMER)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
                assertThat(e.stored(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe 6 the fact write and the append of the event run in one transaction, a failed append stores no fact`() {
        run { e, env ->
            withTrain(e, env) { recorder, run, entity ->
                val before = e.markRequestedIds()
                val factBefore = releaseTrainFact()
                failFunction(FAIL_TRIGGER_ON_OUTBOX)
                sql(
                    "create trigger $FAIL_TRIGGER_ON_OUTBOX before insert on tenancy.outbox for each row " +
                        "when (new.topic = '$MARK_TOPIC') execute function tenancy.$FAIL_TRIGGER_ON_OUTBOX()",
                )
                try {
                    val thrown = runCatching { recorder.handle(step(run, 2, train2, t0.plusSeconds(60))) }
                    assertThat(thrown.isFailure).isTrue()
                } finally {
                    dropInjection()
                }
                assertThat(e.markRequestedIds()).isEqualTo(before)
                assertThat(releaseTrainFact()).isEqualTo(factBefore)
                assertThat(e.state(entity)).isEqualTo("CURRENT" to null)
            }
        }
    }

    @Test
    fun `probe 7 the row of the fact transaction is under the platform tenant with the platform type`() {
        run { e, env ->
            withTrain(e, env) { recorder, run, _ ->
                val before = e.markRequestedIds()
                recorder.handle(step(run, 2, train2, t0.plusSeconds(60)))
                val id = (e.markRequestedIds() - before.toSet()).single()
                val row =
                    BoundaryFixture.asSuperuser { c ->
                        c
                            .prepareStatement(
                                "select tenant_id::text, entity_ref, type from tenancy.outbox where id = ?",
                            ).use { st ->
                                st.setString(1, id)
                                st.executeQuery().use { rs ->
                                    check(rs.next())
                                    Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                                }
                            }
                    }
                assertThat(row)
                    .isEqualTo(Triple(TenantId.PLATFORM.toString(), EntityRef.PLATFORM.value, MARK_TYPE))
            }
        }
    }

    @Test
    fun `probe 8 an append of the look-alike domain type is refused with EmissionNotPermitted and leaves no row`() {
        run { e, env ->
            val before = e.markRequestedIds()
            val writer = env.context.getBean(OutboxWriter::class.java)
            val platform = env.context.getBean(TenantContext::class.java) as PlatformTenantBinding
            val lookAlike = "com.dynokore.gst.tenancy.CertificationMarkRequested.v1"
            val thrown =
                runCatching {
                    platform.bindPlatform("tenancy-svc") {
                        e.tx.inTransaction {
                            writer.append(
                                listOf(OutboundEvent(lookAlike, "{\"trigger\":\"NEW_TRAIN\"}")),
                                EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, "installation"),
                            )
                        }
                    }
                }
            assertThat(thrown.exceptionOrNull()).isInstanceOf(EmissionNotPermitted::class.java)
            assertThat(e.markRequestedIds()).isEqualTo(before)
            val rows =
                BoundaryFixture.asSuperuser { c ->
                    c.prepareStatement("select count(*) from tenancy.outbox where type = ?").use { st ->
                        st.setString(1, lookAlike)
                        st.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            assertThat(rows).isEqualTo(0)
        }
    }

    private fun releaseTrainFact(): String? =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement(RELEASE_TRAIN_SQL).use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
}
