package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/** TPL-18: the composition root. All wiring lives here and in [UseCases]; application and domain stay annotation-free. */
@SpringBootApplication(scanBasePackages = ["com.dynokore.gst.access.adapters", "com.dynokore.gst.access.bootstrap"])
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of section 12.1, TransactionRunner, replicas
    ProductRuntimeConfiguration::class, // L3: replica feeds and fallbacks, platform-event catalogue, fence participants
)
class AccessApplication

fun main(args: Array<String>) {
    runApplication<AccessApplication>(*args)
}
