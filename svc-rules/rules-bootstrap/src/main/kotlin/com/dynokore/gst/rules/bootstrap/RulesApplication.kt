package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * The composition root of rules-svc (TPL-18, ARC-03): all wiring lives here, so `application` stays free of framework
 * annotations. service-runtime's root first, then product-runtime's.
 */
@SpringBootApplication(scanBasePackages = ["com.dynokore.gst.rules.adapters", "com.dynokore.gst.rules.bootstrap"])
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of §12.1, TransactionRunner, replicas
    ProductRuntimeConfiguration::class, // L3: replica feeds and fallbacks, platform-event catalogue, fence participants
)
class RulesApplication

fun main(args: Array<String>) {
    runApplication<RulesApplication>(*args)
}
