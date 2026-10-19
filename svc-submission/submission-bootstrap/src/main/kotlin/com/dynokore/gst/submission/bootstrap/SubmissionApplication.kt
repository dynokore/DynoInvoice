package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * TPL-18: the composition root. All wiring lives in this module so `application` stays free of framework annotations
 * (ARC-03). There is no DataSource bean here or anywhere in the service (owner ruling INV-Q-4196): the one pool is
 * Spring Boot's `dataSource` from `spring.datasource.*` (application.yaml), the `submission_runtime` login.
 */
@SpringBootApplication(
    scanBasePackages = ["com.dynokore.gst.submission.adapters", "com.dynokore.gst.submission.bootstrap"],
)
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of section 12.1, TransactionRunner
    ProductRuntimeConfiguration::class, // L3: replica feeds and fallbacks, platform-event catalogue
)
class SubmissionApplication

fun main(args: Array<String>) {
    runApplication<SubmissionApplication>(*args)
}
