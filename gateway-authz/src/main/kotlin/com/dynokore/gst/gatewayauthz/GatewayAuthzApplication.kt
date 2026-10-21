package com.dynokore.gst.gatewayauthz

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * gateway-authz, the API gateway's token-validation and rate-limit container (INV-ADR-4051, owner ruling
 * INV-Q-4046 A; INV-MOD-2752): no database and no business logic. service-runtime is the composition root (TPL-18);
 * [GatewayAuthzConfiguration] wires the key material, the revocation list, the staleness bound and the protective
 * rate limits.
 */
@SpringBootApplication(proxyBeanMethods = false)
@Import(ServiceRuntimeConfiguration::class, GatewayAuthzConfiguration::class)
class GatewayAuthzApplication

@Suppress("SpreadOperator") // Java varargs: the same entry point as the other bootstrap applications
fun main(args: Array<String>) {
    runApplication<GatewayAuthzApplication>(*args)
}
