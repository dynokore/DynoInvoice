package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.web.EvaluationController
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * The composition root of rules-svc (TPL-18, ARC-03): all wiring lives here, so `application` stays free of framework
 * annotations. service-runtime's root first, then product-runtime's.
 *
 * Scanned by class, not by package name: a string attribute of a library annotation is refused by the bytecode check
 * (bean-definition). The controllers of adapters.web and this package are the components; everything else is a bean
 * of RulesConfiguration.
 */
@SpringBootApplication(scanBasePackageClasses = [EvaluationController::class, RulesApplication::class])
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of §12.1, TransactionRunner, replicas
    ProductRuntimeConfiguration::class, // L3: replica feeds and fallbacks, platform-event catalogue, fence participants
)
class RulesApplication

// The Spring Boot entry point takes the command line as a vararg: one copy of a few strings at start-up.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<RulesApplication>(*args)
}
