package com.dynokore.gst.submission.adapters.clients

import com.dynokore.gst.submission.adapters.clients.access.api.BreakGlassApi
import com.dynokore.gst.submission.adapters.clients.access.model.BreakGlassActionRequest
import com.dynokore.platform.serviceruntime.replica.BreakGlassAction
import com.dynokore.platform.serviceruntime.replica.BreakGlassActionReporter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The report of a marked action was not answered 201 by access-svc; the restricted mode refuses the request. */
class BreakGlassReportFailedException(
    message: String,
) : RuntimeException(message)

/**
 * INV-REQ-897: reports one marked action of an approver of an open break-glass session to access-svc through the
 * client generated from its contract (STK-46, ARC-08), operation `recordBreakGlassAction`. Fail closed: only the
 * status 201 returns; any other status, a timeout or a connection error throws, after exactly one request (no retry).
 * The credentials come from the `internalRestClientBuilder` interceptors (workload token, end-user token, deadline),
 * as for every generated client of the service. The bean exists only when the property naming access-svc is set.
 */
@Component
@ConditionalOnProperty(prefix = "product-runtime.fallback", name = ["access-svc-url"])
class AccessBreakGlassReporter(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    environment: Environment,
) : BreakGlassActionReporter {
    private val api =
        BreakGlassApi(
            builder
                .baseUrl(environment.getRequiredProperty(URL_PROPERTY))
                .requestFactory(
                    JdkClientHttpRequestFactory(
                        HttpClient
                            .newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(READ_TIMEOUT)
                            .build(),
                    ).apply { setReadTimeout(READ_TIMEOUT) },
                ).build(),
        )

    /** `occurredAt` is not sent: the operation has no such member. */
    override fun report(
        unlockRef: String,
        action: BreakGlassAction,
        target: String,
        occurredAt: Instant,
    ) {
        val response =
            api.recordBreakGlassActionWithHttpInfo(
                unlockRef,
                UUID.randomUUID(),
                BreakGlassActionRequest(BreakGlassActionRequest.Action.valueOf(action.name), target),
            )
        if (response.statusCode.value() != HttpStatus.CREATED.value()) {
            throw BreakGlassReportFailedException("recordBreakGlassAction answered ${response.statusCode.value()}")
        }
    }

    private companion object {
        const val URL_PROPERTY = "product-runtime.fallback.access-svc-url"

        /** `x-timeout` of `recordBreakGlassAction` in the access contract. */
        val READ_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
