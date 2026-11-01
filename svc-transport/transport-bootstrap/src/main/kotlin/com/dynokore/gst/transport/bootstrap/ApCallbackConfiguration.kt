package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.http.ApCallbackSignatureFilter
import com.dynokore.gst.transport.adapters.persistence.JooqAcknowledgementEvents
import com.dynokore.gst.transport.adapters.persistence.JooqAttemptOwner
import com.dynokore.gst.transport.adapters.persistence.JooqNotificationLedger
import com.dynokore.gst.transport.adapters.persistence.JooqNotificationUnitOfWork
import com.dynokore.gst.transport.adapters.persistence.VersionCheckedAttemptOwner
import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.ReceiveApNotification
import com.dynokore.gst.transport.domain.SenderVerifier
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The AP callback endpoint (INV-REQ-585): the use case of `receiveApNotification` over the ports of the notification
 * slices, and the sender-verification filter in front of it. The secret of an adapter is read at each request through
 * the service-runtime [SecretStore] at `transport/ap-callback/<adapterId>`.
 */
@Configuration(proxyBeanMethods = false)
class ApCallbackConfiguration {
    @Bean
    fun senderVerifier(clock: Clock): SenderVerifier = SenderVerifier(clock)

    @Bean
    fun receiveApNotification(
        dsl: DSLContext,
        runner: TransactionRunner,
        platform: PlatformTenantBinding,
        scope: TenantScope,
        outbox: OutboxWriter,
    ): ReceiveApNotification =
        ReceiveApNotification(
            VersionCheckedAttemptOwner(
                JooqAttemptOwner(dsl, runner, platform, WORKLOAD_PRINCIPAL),
                dsl,
                scope,
                runner,
                WORKLOAD_PRINCIPAL,
            ),
            JooqNotificationLedger(dsl),
            JooqAcknowledgementEvents(outbox),
            JooqNotificationUnitOfWork(scope, runner, WORKLOAD_PRINCIPAL),
        )

    @Bean
    fun apCallbackSignatureFilter(
        adapters: AccessPointAdapters,
        secrets: SecretStore,
        verifier: SenderVerifier,
    ): FilterRegistrationBean<ApCallbackSignatureFilter> =
        FilterRegistrationBean(ApCallbackSignatureFilter(adapters, secrets, verifier)).apply {
            order = FILTER_ORDER
            urlPatterns = listOf("/v1/ap/*")
        }

    private companion object {
        const val WORKLOAD_PRINCIPAL = "svc:transport-svc"

        // after the runtime's workload-token (-90) and sync-edge (-85) filters
        const val FILTER_ORDER = -80
    }
}
