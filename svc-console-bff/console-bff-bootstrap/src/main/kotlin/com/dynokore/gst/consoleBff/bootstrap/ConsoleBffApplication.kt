package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.AdaptersPackage
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * TPL-18: the composition root. All wiring lives in this module so `application` stays free of framework annotations
 * (ARC-03). This slice has no database (INV-Q-17448 C: the datasource, the jOOQ schema and the migrations arrive with
 * INV-T-14458) and registers no console request filter and no controller (the limits and the session belong to later
 * tasks).
 */
@SpringBootApplication(
    // classes, not package names: platform-build's raw-SQL placement gate keeps no String attribute of this annotation
    scanBasePackageClasses = [AdaptersPackage::class, ConsoleBffApplication::class],
)
@Import(
    ServiceRuntimeConfiguration::class, // L1 composition root: bindings of section 12.1, TransactionRunner
)
class ConsoleBffApplication

// The spread copies the command line once, at start-up.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<ConsoleBffApplication>(*args)
}
