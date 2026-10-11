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

plugins {
    id("java-platform")
    `maven-publish`
}

version = providers.gradleProperty("bomVersion").getOrElse("0.0.0-local")

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
    }
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
