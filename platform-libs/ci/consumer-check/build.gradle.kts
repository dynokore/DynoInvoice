// ci/consumer-check/build.gradle.kts — INV-T-4356 (see settings.gradle.kts).
//
// The consumer a service's adapters and bootstrap are (TPL-01): the BOM and impl artefacts only, no `-api`,
// tenancy-kernel or third-party coordinate. `check` (run by the CI job) prints the resolved graph, compiles
// ConsumerCheck.java against a type of each module's `-api`, and runs it, loading one impl class of each module.

plugins {
    java
}

val bomVersion: String =
    providers.gradleProperty("bomVersion").orNull
        ?: throw GradleException("consumer-check: -PbomVersion=<the platform-libs-bom version just published> is required")

/** The impl artefacts this consumer declares; everything else must come from their POMs. */
val implArtefacts = listOf("service-runtime", "outbox-and-queue", "audit-log", "config-resolver", "tenancy-kernel")

dependencies {
    implementation(platform("com.dynokore.platform:platform-libs-bom:$bomVersion"))
    implArtefacts.forEach { implementation("com.dynokore.platform:$it") }
}

// Deny by default: the declared dependencies are exactly the BOM and the impl artefacts above, so a coordinate added
// here by hand (an `-api`, tenancy-kernel, a third-party library) fails the check rather than masking a POM defect.
val declared =
    configurations.flatMap { c -> c.dependencies.map { "${c.name} ${it.group}:${it.name}:${it.version ?: ""}" } }.sorted()
val allowed =
    (listOf("implementation com.dynokore.platform:platform-libs-bom:$bomVersion") +
        implArtefacts.map { "implementation com.dynokore.platform:$it:" }).sorted()
if (declared != allowed) {
    throw GradleException("consumer-check: declares $declared; only the BOM and the impl artefacts $implArtefacts are allowed")
}

val printResolvedGraph = tasks.register("printResolvedGraph") {
    val runtime = configurations.runtimeClasspath
    doLast {
        val components =
            runtime.get().incoming.resolutionResult.allComponents
                .map { it.id }.filterIsInstance<ModuleComponentIdentifier>()
                .map { "${it.group}:${it.module}:${it.version}" }.sorted()
        logger.lifecycle("consumer-check: runtime classpath resolved from platform-libs-bom $bomVersion and $implArtefacts:")
        components.forEach { logger.lifecycle("  $it") }
        logger.lifecycle("consumer-check: ${components.size} modules")
        val missing =
            (implArtefacts.flatMap { listOf(it, "$it-api") })
                .filter { name -> components.none { it.startsWith("com.dynokore.platform:$name:") } }
        if (missing.isNotEmpty()) throw GradleException("consumer-check: not resolved transitively: $missing")
    }
}

val runConsumer = tasks.register<JavaExec>("runConsumer") {
    dependsOn(printResolvedGraph)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumercheck.ConsumerCheck")
}

tasks.named("check") { dependsOn(runConsumer) }
