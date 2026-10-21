package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.AdaptersPackage
import com.dynokore.platform.productruntime.ProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * TPL-18: the composition root. All wiring lives in this module so `application` stays free of framework annotations
 * (ARC-03). There is no DataSource bean here or anywhere in the service (owner ruling INV-Q-4196): the one pool is
 * Spring Boot's `dataSource` from `spring.datasource.*` (application.yaml), the `evidence_runtime` login.
 */
@SpringBootApplication(
    // classes, not package names: platform-build's raw-SQL placement gate keeps no String attribute of this annotation
    scanBasePackageClasses = [AdaptersPackage::class, EvidenceApplication::class],
)
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of section 12.1, TransactionRunner
    ProductRuntimeConfiguration::class, // L3: replica feeds and fallbacks, platform-event catalogue
)
class EvidenceApplication

// The spread copies the command line once, at start-up.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<EvidenceApplication>(*args)
}
