package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.AdaptersRoot
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import

/**
 * TPL-18: the composition root. All wiring lives here, in [ServiceDefaults], [UseCases] and [KeyServingUseCases];
 * application and domain stay annotation-free.
 */
@SpringBootApplication(scanBasePackageClasses = [AdaptersRoot::class, MasterdataApplication::class])
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of section 12.1, TransactionRunner, replicas
    ProductRuntimeConfiguration::class, // L3: replica feeds and fallbacks, platform-event catalogue, fence participants
)
class MasterdataApplication

fun main(args: Array<String>) {
    SpringApplication.run(arrayOf<Class<*>>(MasterdataApplication::class.java), args)
}
