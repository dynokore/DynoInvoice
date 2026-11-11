package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.SignedLinkRequestedOutbox
import com.dynokore.gst.masterdata.adapters.http.LinkSubmissionAudit
import com.dynokore.gst.masterdata.adapters.persistence.JooqCounterpartyRequests
import com.dynokore.gst.masterdata.adapters.persistence.JooqLinkBindings
import com.dynokore.gst.masterdata.adapters.registry.PolicySendMethod
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.request.CounterpartyRequests
import com.dynokore.gst.masterdata.application.request.LinkBindings
import com.dynokore.gst.masterdata.application.request.LinkValidityLimits
import com.dynokore.gst.masterdata.application.request.RequestStores
import com.dynokore.gst.masterdata.application.request.SendCounterpartyRequest
import com.dynokore.gst.masterdata.application.request.SendMethodPolicy
import com.dynokore.gst.masterdata.application.request.SendPolicies
import com.dynokore.gst.masterdata.application.request.SignedLinkRequestedPublisher
import com.dynokore.gst.masterdata.application.request.SubmissionAudit
import com.dynokore.gst.masterdata.application.request.SubmitCounterpartyDetails
import com.dynokore.gst.masterdata.application.request.TenantLinkValidity
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeParseException

/**
 * The link validity limits of the installation, read from configuration (INV-AC-18555, owner answer relayed 2026-11-08:
 * default 7 days, maximum 30 days, configurable, never code). The two keys are ISO-8601 durations of whole days; the
 * service refuses to start, naming the key, when a key is absent or blank, unparseable, zero or negative, or not a
 * whole number of days (the unit of the limits port), or when the default is greater than the maximum. No default is
 * applied for an absent key.
 */
class ConfiguredLinkValidityLimits private constructor(
    override val defaultDays: Long,
    override val maxDays: Long,
) : LinkValidityLimits {
    companion object {
        const val DEFAULT_KEY = "masterdata.counterparty-request.link-validity.default"
        const val MAX_KEY = "masterdata.counterparty-request.link-validity.max"

        fun from(environment: Environment): ConfiguredLinkValidityLimits =
            of(
                environment.getProperty(DEFAULT_KEY),
                environment.getProperty(MAX_KEY),
            )

        fun of(
            defaultText: String?,
            maxText: String?,
        ): ConfiguredLinkValidityLimits {
            val default = days(DEFAULT_KEY, defaultText)
            val max = days(MAX_KEY, maxText)
            check(default <= max) { "$DEFAULT_KEY ($defaultText) is greater than $MAX_KEY ($maxText)" }
            return ConfiguredLinkValidityLimits(default, max)
        }

        private fun days(
            key: String,
            text: String?,
        ): Long {
            check(!text.isNullOrBlank()) { "$key is not configured" }
            val duration =
                try {
                    Duration.parse(text.trim())
                } catch (_: DateTimeParseException) {
                    error("$key is not an ISO-8601 duration: $text")
                }
            check(!duration.isNegative && !duration.isZero) { "$key must be positive: $text" }
            check(duration.toDaysPart() * SECONDS_PER_DAY == duration.seconds && duration.nano == 0) {
                "$key must be a whole number of days: $text"
            }
            return duration.toDays()
        }

        private const val SECONDS_PER_DAY = 86_400L
    }
}

/**
 * TPL-18: the counterparty requests (INV-T-18221, INV-REQ-901, INV-REQ-902, INV-REQ-1291). VP-30 is read through the
 * policy reader on every send; the tenant's own validity has no adapter yet (the channel by which a tenant sets it is
 * an open design question), so every tenant has the default.
 */
@Suppress("TooManyFunctions") // one bean method per wired part, as UseCases does (INV-Q-4629)
@Configuration(proxyBeanMethods = false)
class CounterpartyRequestUseCases {
    @Bean
    fun counterpartyRequests(
        dsl: DSLContext,
        clock: Clock,
    ): CounterpartyRequests = JooqCounterpartyRequests(dsl, clock)

    @Bean
    fun linkBindings(dsl: DSLContext): JooqLinkBindings = JooqLinkBindings(dsl)

    @Bean
    fun requestStores(
        requests: CounterpartyRequests,
        bindings: JooqLinkBindings,
    ): RequestStores = RequestStores(requests, bindings as LinkBindings)

    @Bean
    fun linkValidityLimits(environment: Environment): LinkValidityLimits =
        ConfiguredLinkValidityLimits.from(environment)

    @Bean
    fun sendMethodPolicy(
        policy: PolicyReader,
        tenants: TenantContext,
        clock: Clock,
    ): SendMethodPolicy = PolicySendMethod(policy, tenants, clock)

    /** No tenant sets its own validity yet: every tenant has the default of [LinkValidityLimits]. */
    @Bean
    fun tenantLinkValidity(): TenantLinkValidity = TenantLinkValidity { null }

    @Bean
    fun sendPolicies(
        method: SendMethodPolicy,
        limits: LinkValidityLimits,
        tenantValidity: TenantLinkValidity,
    ) = SendPolicies(method, limits, tenantValidity)

    @Bean
    fun signedLinkRequestedPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): SignedLinkRequestedPublisher = SignedLinkRequestedOutbox(outbox, mapper, tenants)

    @Bean
    fun submissionAudit(
        writer: AuditWriter,
        tenants: TenantContext,
        requests: CounterpartyRequests,
    ): SubmissionAudit = LinkSubmissionAudit(writer, tenants, requests)

    @Bean
    @Suppress("LongParameterList")
    fun sendCounterpartyRequest(
        tx: TransactionRunner,
        stores: RequestStores,
        policies: SendPolicies,
        tenants: TenantContext,
        clock: Clock,
        publisher: SignedLinkRequestedPublisher,
    ) = SendCounterpartyRequest(tx, stores, policies, { tenants.current().toString() }, clock, publisher)

    @Bean
    @Suppress("LongParameterList")
    fun submitCounterpartyDetails(
        tx: TransactionRunner,
        stores: RequestStores,
        parties: CounterpartyRepository,
        clock: Clock,
        publisher: MasterDataResolvedPublisher,
        audit: SubmissionAudit,
    ) = SubmitCounterpartyDetails(tx, stores, parties, clock, publisher, audit)
}
