package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobsHarness
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant

/**
 * The workload principal as the composition passes it (owner ruling INV-Q-4344 (b)), on PostgreSQL 16, 17 and 18:
 * the outbox-and-queue wiring's [com.dynokore.platform.outboxandqueue.TenantScope] binds `svc:<svc>` for the relay's
 * transactions, and the job runtime binds `svc:<svc>/job:<job>` for a scheduled run.
 */
class WorkloadActorIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `app-actor is svc inside a relay transaction and svc-job inside a scheduled run`(image: String) {
        JobsHarness(image).use { h ->
            val wiring = OutboxAndQueueWiring()
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val scope = wiring.tenantScope(replica.tenants, replica.tx, replica.tx, wiring.workloadPrincipal(gst()))
            scope.workloadPrincipal shouldBe "svc:$SERVICE"

            // a scheduled run: its rows carry the job's own principal
            val def = job("actor-check", scope = JobEntityScope.PLATFORM)
            replica.runtime.register(def) { }
            h.fixture.captureActors("$SCHEMA.job_run")
            replica.runtime.fire(def.name)
            h.fixture.capturedActors("$SCHEMA.job_run") shouldBe listOf(WorkloadPrincipal(SERVICE).job(def.name))

            // the relay publishes the run's events, each in a transaction bound to the service's principal
            h.fixture.captureActors("$SCHEMA.outbox")
            h.fixture.runAsAdmin(
                "create trigger capture_actor_relay after update on $SCHEMA.outbox for each row execute function public.capture_actor()",
            )
            val published = mutableListOf<String>()
            val binding =
                object : EventBinding {
                    override fun publish(
                        topic: String,
                        envelope: Envelope,
                    ) {
                        published += topic
                    }
                }
            val relay = wiring.outboxRelay(h.fixture.runtimeDataSource(), scope, binding, ModuleProperties(schema = SCHEMA), Clock.systemUTC())
            relay.runOnce()
            published.shouldNotBeEmpty()
            h.fixture.capturedActors("$SCHEMA.outbox").toSet() shouldBe setOf("svc:$SERVICE")
        }
    }

    private fun gst() = GstProperties(service = SERVICE)
}
