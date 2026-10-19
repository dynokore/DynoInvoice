package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/** The composition root of control-plane-svc (TPL-18). */
@SpringBootApplication(
    scanBasePackages = ["com.dynokore.gst.controlPlane.adapters", "com.dynokore.gst.controlPlane.bootstrap"],
)
@Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)
class ControlPlaneApplication

fun main(args: Array<String>) {
    runApplication<ControlPlaneApplication>(*args)
}
