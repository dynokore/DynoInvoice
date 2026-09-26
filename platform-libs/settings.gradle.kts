// settings.gradle.kts — STK-04, STK-60 (INV-T-2860 / T16).
//
// The `gst.*` convention plugins (`gst.kotlin-base`, `gst.library`, STK-59) and the
// shared Konsist rule set (`konsist-rules`, INV-T-2846) are not published to a
// registry yet — platform-build's own README says "design only" and its own
// settings.gradle.kts consumes `build-logic` the same way: `pluginManagement {
// includeBuild(...) }` against a sibling checkout, not a Maven repository entry.
// This repository does the same against a sibling checkout of `platform-build`
// itself (named in the MR as the chosen consumption method, per the coordinator's
// brief). CI clones that sibling first (`.gitlab-ci.yml`); a local build needs
// `platform-build` checked out next to this repository (`../platform-build`), the
// same layout `~/projects/invoice/` already uses.
//
// Replace this with a `maven { url = ... }` plugin repository and a regular
// version-catalogue dependency once STK-71's registry accepts cross-project
// publish/consume (ci/README.md's "package registry" note names the same gap for
// publishing) — a provisional default, not a fork of the convention (AGT-04).
pluginManagement {
    includeBuild("../platform-build/build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The whole `platform-build` repository, included as a composite build (the same
// technique `build-logic`'s own functional tests use on themselves,
// `ArchitectureRulesFunctionalTest.kt`), so Gradle substitutes the real
// `:konsist-rules` project for the `com.dynokore.gst.build:konsist-rules`
// coordinate that `platform-libs-bom`'s test sources declare (`testImplementation`)
// — the shared Konsist architecture rule set, not published either.
includeBuild("../platform-build")

rootProject.name = "platform-libs"

// Wave 1 modules only (repository README.md's wave table; AGT-11: start a task
// only when its dependencies are merged). Wave 2-4 modules (party-registry,
// calendar-and-periods, document-lifecycle, integration-canon,
// reconciliation-engine) stay README-only directories until their own Keel task
// scaffolds them.
include(
    "platform-libs-bom",
    "service-runtime",
    "outbox-and-queue",
    "rulepack-kernel",
    "tenancy-kernel",
    "evidence-store",
    "adapter-runtime",
    "config-resolver",
    "audit-log",
    "product-runtime",
)
