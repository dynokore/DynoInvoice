package com.dynokore.gst.gatewayauthz.check

import com.google.rpc.Code
import com.google.rpc.Status
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc
import io.envoyproxy.envoy.service.auth.v3.CheckRequest
import io.envoyproxy.envoy.service.auth.v3.CheckResponse
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse
import io.envoyproxy.envoy.type.v3.HttpStatus
import io.envoyproxy.envoy.type.v3.StatusCode
import io.grpc.stub.StreamObserver

/**
 * Envoy's external-authorisation API, envoy.service.auth.v3.Authorization/Check (STK-70, INV-ADR-4051), served on
 * the pod-local Unix socket by Spring Boot's gRPC server (coordinator ruling INV-Q-4077). It maps the Check request
 * to [ExtAuthorizer] and its decision back: an allowed request keeps its headers (the Authorization header unchanged)
 * minus the inbound identity headers, and the API-30 fields go to the client response; a denied one gets the status,
 * fields and error envelope.
 */
class ExtAuthzService(
    private val authorizer: ExtAuthorizer,
) : AuthorizationGrpc.AuthorizationImplBase() {
    override fun check(
        request: CheckRequest,
        responseObserver: StreamObserver<CheckResponse>,
    ) {
        responseObserver.onNext(respond(authorizer.check(toAuthzRequest(request))))
        responseObserver.onCompleted()
    }

    private fun toAuthzRequest(request: CheckRequest): AuthzRequest {
        val attributes = request.attributes
        val http = attributes.request.http
        return AuthzRequest(
            headers = http.headersMap.mapKeys { it.key.lowercase() },
            path = http.path,
            context = attributes.contextExtensionsMap,
            requestId = http.id,
        )
    }

    private fun respond(decision: AuthzDecision): CheckResponse {
        val response = CheckResponse.newBuilder()
        if (decision.allowed) {
            val ok = OkHttpResponse.newBuilder().addAllHeadersToRemove(decision.headersToRemove)
            decision.responseHeaders.forEach { (name, value) -> ok.addResponseHeadersToAdd(header(name, value)) }
            response.setStatus(Status.newBuilder().setCode(Code.OK_VALUE)).setOkResponse(ok)
        } else {
            val denied =
                DeniedHttpResponse.newBuilder()
                    .setStatus(HttpStatus.newBuilder().setCode(StatusCode.forNumber(decision.status)))
                    .setBody(decision.body)
            decision.responseHeaders.forEach { (name, value) -> denied.addHeaders(header(name, value)) }
            response.setStatus(Status.newBuilder().setCode(grpcCodeOf(decision.status))).setDeniedResponse(denied)
        }
        return response.build()
    }

    private fun header(
        name: String,
        value: String,
    ): HeaderValueOption = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey(name).setValue(value))
        .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
        .build()

    private fun grpcCodeOf(status: Int): Int = when (status) {
        HTTP_UNAUTHORIZED -> Code.UNAUTHENTICATED_VALUE
        HTTP_TOO_MANY_REQUESTS -> Code.RESOURCE_EXHAUSTED_VALUE
        HTTP_UNAVAILABLE -> Code.UNAVAILABLE_VALUE
        else -> Code.PERMISSION_DENIED_VALUE
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAVAILABLE = 503
    }
}
