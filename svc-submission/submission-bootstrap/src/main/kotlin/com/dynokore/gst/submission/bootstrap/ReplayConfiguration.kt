package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqReplayCandidateSource
import com.dynokore.gst.submission.adapters.persistence.JooqReplayRequeue
import com.dynokore.gst.submission.adapters.persistence.JooqReplayVersionLookup
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayCandidateSource
import com.dynokore.gst.submission.application.replay.ReplayRequeue
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.ReplayVersionLookup
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** TPL-18 wiring of the replay by scope (INV-REQ-575): the two store adapters and the use case over the replica. */
@Configuration(proxyBeanMethods = false)
class ReplayConfiguration {
    @Bean
    fun replayCandidateSource(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ReplayCandidateSource = JooqReplayCandidateSource(dsl, runner)

    @Bean
    fun replayRequeue(
        dsl: DSLContext,
        runner: TransactionRunner,
        versions: InFlightStore,
        queue: WorkQueue,
    ): ReplayRequeue = JooqReplayRequeue(dsl, runner, versions, queue)

    @Bean
    fun replayBySelection(
        replica: AuthorityReplica,
        source: ReplayCandidateSource,
        requeue: ReplayRequeue,
    ): ReplayBySelection = ReplayBySelection(replica, source, requeue)

    @Bean
    fun replayVersionLookup(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ReplayVersionLookup = JooqReplayVersionLookup(dsl, runner)

    @Bean
    fun replaySingleVersion(
        replica: AuthorityReplica,
        lookup: ReplayVersionLookup,
        requeue: ReplayRequeue,
    ): ReplaySingleVersion = ReplaySingleVersion(replica, lookup, requeue)
}
