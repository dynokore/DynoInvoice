package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.AdaptersPackage
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/** The composition root of control-plane-svc (TPL-18). */
@SpringBootApplication(scanBasePackageClasses = [AdaptersPackage::class, ControlPlaneApplication::class])
@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)
class ControlPlaneApplication

@Suppress("SpreadOperator") // the Spring Boot entry point passes its arguments through
fun main(args: Array<String>) {
    runApplication<ControlPlaneApplication>(*args)
}
