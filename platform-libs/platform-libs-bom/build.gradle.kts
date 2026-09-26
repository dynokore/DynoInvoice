// platform-libs-bom/build.gradle.kts — STK-60's platform BOM (FR-L.6; INV-T-2860 / T16).
//
// "A bill of materials names a set of module versions tested together; a
// service bumps that one line" (repository README.md). A `java-platform`
// module publishes no code of its own — only Maven `dependencyManagement`
// entries — so it cannot also apply `gst.kotlin-base`/`gst.library` (Gradle
// does not allow `java-platform` alongside `java`/`kotlin-jvm` in one project):
// its own version and changelog are handled the same way as every other module
// (a `version.txt` and a `CHANGELOG.md` next to this file) without the
// convention plugin.
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

version = file("version.txt").readText().trim()

dependencies {
    constraints {
        // One pair per Wave 1 module, added by that module's own task, in STK-60's
        // canonical L1 order (service-runtime, outbox-and-queue, rulepack-kernel,
        // tenancy-kernel, evidence-store, adapter-runtime, config-resolver,
        // audit-log), then product-runtime.
        api("com.dynokore.platform:rulepack-kernel-api:${project(":rulepack-kernel").version}") // INV-T-2866 / T22
        api("com.dynokore.platform:rulepack-kernel:${project(":rulepack-kernel").version}") // INV-T-2866 / T22
        api("com.dynokore.platform:tenancy-kernel-api:${project(":tenancy-kernel").version}") // INV-T-2861 / T17
        api("com.dynokore.platform:tenancy-kernel:${project(":tenancy-kernel").version}") // INV-T-2861 / T17
        api("com.dynokore.platform:evidence-store-api:${project(":evidence-store").version}") // INV-T-2863 / T19
        api("com.dynokore.platform:evidence-store:${project(":evidence-store").version}") // INV-T-2863 / T19
        api("com.dynokore.platform:adapter-runtime-api:${project(":adapter-runtime").version}") // INV-T-2862 / T18
        api("com.dynokore.platform:adapter-runtime:${project(":adapter-runtime").version}") // INV-T-2862 / T18
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
}
