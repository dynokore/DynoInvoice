// platform-libs-bom/build.gradle.kts — STK-60's platform BOM (FR-L.6; INV-T-2860 / T16).
//
// "A bill of materials names a set of module versions tested together; a
// service bumps that one line" (repository README.md). A `java-platform`
// module publishes no code of its own — only Maven `dependencyManagement`
// entries — so it cannot also apply `gst.kotlin-base`/`gst.library` (Gradle
// does not allow `java-platform` alongside `java`/`kotlin-jvm` in one project):
// it has no hand-edited version or changelog file: CI's `ci/bom-version.sh` derives the
// version (latest published minor + 1, only when the constraint set differs) and passes
// it as `-PbomVersion`; the changelog entry is published inside the artefact (README.md,
// "Releasing"). A local build gets `0.0.0-local`.
//
// Done-when "an empty module set releases end to end": no `constraints` are
// declared yet because no Wave 1 module has a real interface yet (every one is
// still the design-only scaffold INV-T-2860 adds) — there is nothing to pin a
// version of. This is deliberately the repository's true current state, not a
// fixture, and this module's own Maven publish (`.gitlab-ci.yml`,
// `MAVEN_PUBLISH: "true"`) proves the release plumbing works in that state. A
// module adds its own `api`/impl constraint pair here in the same merge request
// that gives it a real interface, in STK-60's canonical order.
//
// The tested set (STK-60 "fixes a tested set"; INV-T-2877, INV-Q-3261): every constraint below is a module the
// required CI `contract-tests` job (contract-tests/, the consumer-driven contract suite of every Wave 1 module;
// INV-Q-3925) ran against in the same main pipeline, before maven-publish versions and publishes this BOM; gst-catalogue,
// which is not a platform-libs module, is pinned by gradle/libs.versions.toml and recorded with the BOM version in the
// task's done evidence.

plugins {
    id("java-platform")
    `maven-publish`
    // Only for gst.build.CompanyLicence below (INV-T-4310): `java-platform` cannot apply gst.kotlin-base, which
    // stamps every other module's POMs, so the plugin is put on this script's classpath without being applied.
    id("gst.kotlin-base") apply false
}

// The company licence in the BOM's POM (owner ruling INV-Q-4286 (C), STK-66, INV-T-4310): the same values and the
// same check as every gst.kotlin-base publication, read from platform-build's one licence file. generatePomFile*
// fails unless the POM declares exactly that licence.
gst.build.CompanyLicence.applyTo(project)

version = providers.gradleProperty("bomVersion").getOrElse("0.0.0-local")

// Needed only for the `platform(libs.jackson.bom)` import below (published as a Maven
// `<scope>import</scope>` entry); this BOM still declares no plain dependency of its own.
javaPlatform {
    allowDependencies()
}

// Every constraint below reads `project(":<module>").version`, which is `unspecified` until that
// module's own build file has been evaluated. Evaluate each constrained module first.
listOf(
    "service-runtime", "outbox-and-queue", "rulepack-kernel", "tenancy-kernel",
    "evidence-store", "adapter-runtime", "config-resolver", "audit-log", "crypto", "tenancy-jooq", "product-runtime",
    "calendar-and-periods", "party-registry",
).forEach { evaluationDependsOn(":$it") }

dependencies {
    constraints {
        // One pair per Wave 1 module, added by that module's own task, in STK-60's
        // canonical L1 order (service-runtime, outbox-and-queue, rulepack-kernel,
        // tenancy-kernel, evidence-store, adapter-runtime, config-resolver,
        // audit-log), then product-runtime.
        api("com.dynokore.platform:service-runtime-api:${project(":service-runtime").version}") // INV-T-2871 / T27
        api("com.dynokore.platform:service-runtime:${project(":service-runtime").version}") // INV-T-2871 / T27
        api("com.dynokore.platform:outbox-and-queue-api:${project(":outbox-and-queue").version}") // INV-T-2995 / FU-C1
        api("com.dynokore.platform:outbox-and-queue:${project(":outbox-and-queue").version}") // INV-T-2995 / FU-C1
        api("com.dynokore.platform:rulepack-kernel-api:${project(":rulepack-kernel").version}") // INV-T-2866 / T22
        api("com.dynokore.platform:rulepack-kernel:${project(":rulepack-kernel").version}") // INV-T-2866 / T22
        api("com.dynokore.platform:tenancy-kernel-api:${project(":tenancy-kernel").version}") // INV-T-2861 / T17
        api("com.dynokore.platform:tenancy-kernel:${project(":tenancy-kernel").version}") // INV-T-2861 / T17
        api("com.dynokore.platform:evidence-store-api:${project(":evidence-store").version}") // INV-T-2863 / T19
        api("com.dynokore.platform:evidence-store:${project(":evidence-store").version}") // INV-T-2863 / T19
        api("com.dynokore.platform:adapter-runtime-api:${project(":adapter-runtime").version}") // INV-T-2862 / T18
        api("com.dynokore.platform:adapter-runtime:${project(":adapter-runtime").version}") // INV-T-2862 / T18
        api("com.dynokore.platform:config-resolver-api:${project(":config-resolver").version}") // INV-T-2869 / T25
        api("com.dynokore.platform:config-resolver:${project(":config-resolver").version}") // INV-T-2869 / T25
        api("com.dynokore.platform:audit-log-api:${project(":audit-log").version}") // INV-T-2868 / T24, INV-T-2999, INV-T-3001
        api("com.dynokore.platform:audit-log:${project(":audit-log").version}") // INV-T-2868 / T24, INV-T-2999, INV-T-3001
        // crypto (CS-53; INV-Q-3356 M-A): not yet in STK-60's L1 list, so after audit-log for now.
        api("com.dynokore.platform:crypto-api:${project(":crypto").version}") // INV-T-3358 / FU-E2
        api("com.dynokore.platform:crypto:${project(":crypto").version}") // INV-T-3358 / FU-E2
        // tenancy-jooq (INV-T-4133): not yet in STK-60's L1 list, so after crypto for now.
        api("com.dynokore.platform:tenancy-jooq-api:${project(":tenancy-jooq").version}") // INV-T-4133
        api("com.dynokore.platform:tenancy-jooq:${project(":tenancy-jooq").version}") // INV-T-4133
        api("com.dynokore.platform:product-runtime-api:${project(":product-runtime").version}") // INV-T-2876 / T32
        api("com.dynokore.platform:product-runtime:${project(":product-runtime").version}") // INV-T-2876 / T32
        // calendar-and-periods (INV-T-4963): the first L2 module, after the L1 modules and product-runtime.
        api("com.dynokore.platform:calendar-and-periods-api:${project(":calendar-and-periods").version}") // INV-T-4963
        api("com.dynokore.platform:calendar-and-periods:${project(":calendar-and-periods").version}") // INV-T-4963
        // party-registry (INV-T-4964): the second L2 module.
        api("com.dynokore.platform:party-registry-api:${project(":party-registry").version}") // INV-T-4964
        api("com.dynokore.platform:party-registry:${project(":party-registry").version}") // INV-T-4964

        // CVE-2026-65182, CVE-2026-65905, CVE-2026-68525: service-runtime (INV-T-2871 /
        // T27) is this repository's first module on Spring Boot 4.1.1, whose managed
        // Tomcat (tomcat-embed-core/-el/-websocket 11.0.24) carries all three CRITICAL
        // CVEs — the same version certification-kit already had to patch directly in
        // its own build.gradle.kts (MR !1, commit 0ae3d40). Every consumer of this BOM
        // is a consumer of Spring Boot 4.1.1 through service-runtime, so the constraint
        // belongs here rather than in each consumer: it reaches the first module, or
        // first service `bootstrap`, that adds spring-boot-starter-web, instead of that
        // consumer rediscovering the CVE independently.
        api(libs.tomcatEmbed.core)
        api(libs.tomcatEmbed.el)
        api(libs.tomcatEmbed.websocket)

        // Coordinator ruling INV-Q-4643 (A), delegated (owner 2026-10-14; INV-T-4645): jOOQ 3.21.9's io.r2dbc:r2dbc-spi
        // brings org.reactivestreams:reactive-streams 1.0.3, CC0-1.0, which is not in STK-66; 1.0.4 is the same 13 classes
        // under MIT-0 (INV-Q-4284). Pinned here, like Tomcat and Jackson, so a service that imports this BOM and declares
        // jOOQ directly resolves 1.0.4 too.
        api(libs.reactiveStreams)
    }

    // CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777
    // (HIGH, INV-REQ-1382; INV-T-4230): Spring Boot 4.1.1 imports tools.jackson:jackson-bom
    // 3.1.5, whose jackson-core/-databind carry all five. Importing Jackson's own BOM at the
    // catalogue's `jackson` version (3.2.3) constrains every `tools.jackson` artefact the
    // Spring Boot BOM manages (jackson-core, jackson-databind and every module), not a
    // hand-kept subset, the same way the Tomcat constraint above patches Spring Boot's
    // Tomcat. A consumer importing both BOMs resolves the higher version, 3.2.3.
    api(platform(libs.jackson.bom))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
            // The changelog entry computed by ci/bom-version.sh, published with the BOM.
            providers.gradleProperty("bomChangelog").orNull?.let {
                artifact(file(it)) {
                    classifier = "changelog"
                    extension = "txt"
                }
            }
        }
    }
}
