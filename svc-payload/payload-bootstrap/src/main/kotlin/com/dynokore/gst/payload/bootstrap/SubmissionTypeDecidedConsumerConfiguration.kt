package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.events.SubmissionTypeDecidedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildInputs
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of payload-svc to `SubmissionTypeDecided` and the beans of the build trigger, which the canonical
 * input consumer reuses. The consumer name stays `payload-svc`: the offset is keyed by (consumer, topic).
 */
@Configuration(proxyBeanMethods = false)
class SubmissionTypeDecidedConsumerConfiguration {
    @Bean
    fun buildJobs(
        dsl: DSLContext,
        queue: WorkQueue,
        tenants: TenantContext,
    ): JooqBuildJobs = JooqBuildJobs(dsl, queue) { tenants.current().value }

    @Bean
    fun typeDecisionStore(
        dsl: DSLContext,
        jobs: JooqBuildJobs,
        tenants: TenantContext,
    ): JooqTypeDecisionStore = JooqTypeDecisionStore(dsl, jobs) { tenants.current().value }

    @Bean
    fun buildInputs(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqBuildInputs = JooqBuildInputs(dsl) { tenants.current().value }

    @Bean
    fun buildTrigger(
        inputs: JooqBuildInputs,
        decisions: JooqTypeDecisionStore,
        jobs: JooqBuildJobs,
    ): BuildTrigger = BuildTrigger(inputs, decisions, jobs)

    /**
     * Dead-letter replay with two inbox consumers: each replayed item goes to the consumer of its envelope type, and
     * an item of any other type stays queued (fail closed). The platform default is ambiguous with two consumers.
     */
    @Bean
    @Suppress("LongParameterList")
    fun replayDelivery(
        scope: TenantScope,
        dsl: DSLContext,
        limiter: LaneRateLimiter,
        clock: Clock,
        @Qualifier("versionFactsConsumer") versionFacts: JdbcInboxConsumer,
        @Qualifier("submissionTypeDecidedConsumer") decisions: JdbcInboxConsumer,
    ): ReplayDelivery =
        TypeRoutingReplayDelivery(
            // the replay items are queued under the consumer name; the default queue claims only unnamed items
            JdbcWorkQueue(scope, dsl, SCHEMA, limiter, clock, CONSUMER),
            scope,
            mapOf(VERSION_FACTS_TYPE to versionFacts, TYPE to decisions),
        )

    @Bean
    @Suppress("LongParameterList")
    fun submissionTypeDecidedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        decisions: JooqTypeDecisionStore,
        trigger: BuildTrigger,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            SubmissionTypeDecidedHandler(decisions, trigger),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "payload-svc"
        const val TOPIC = "gst.submission.submission-type-decided.v1"
        const val VERSION_FACTS_TYPE = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
        const val TYPE = "com.dynokore.gst.submission.SubmissionTypeDecided.v1"
        const val SCHEMA = "payload"
    }
}

/**
 * Routes each replay item to the inbox consumer of its envelope `type` (exact string equality). It claims the item,
 * applies it through [JdbcInboxConsumer.applyReplayed] and completes it in one tenant transaction. An unreadable
 * envelope, a missing type or an unmapped type applies nothing, releases the item and throws: it stays queued.
 */
class TypeRoutingReplayDelivery(
    private val queue: WorkQueue,
    private val scope: TenantScope,
    private val routes: Map<String, JdbcInboxConsumer>,
    private val mapper: ObjectMapper = ObjectMapper(),
) : ReplayDelivery {
    override fun runOnce(): ReplayOutcome? {
        val item = queue.claimNext() ?: return null
        try {
            val raw = inTenant(item) { mapper.readTree(queue.payloadOf(item)).get("raw").asString() }
            val type = typeOf(raw)
            val consumer = routes[type] ?: error("no replay route for envelope type $type")
            return inTenant(item) {
                val outcome = consumer.applyReplayed(raw, item.tenant)
                queue.complete(item)
                outcome
            }
        } catch (expected: Exception) {
            inTenant(item) { queue.release(item) }
            throw expected
        }
    }

    private fun typeOf(raw: String): String? =
        try {
            mapper
                .readTree(raw)
                .get("type")
                ?.takeIf { it.isString }
                ?.asString()
        } catch (e: tools.jackson.core.JacksonException) {
            throw IllegalStateException("the replayed envelope is not readable", e)
        }

    private fun <T> inTenant(
        item: ClaimedItem,
        block: () -> T,
    ): T = scope.bindWorkload(item.tenant) { scope.transactions.inTransaction { block() } }
}
