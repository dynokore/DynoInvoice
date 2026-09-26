// catalogue/generator/build.gradle.kts — INV-T-2851 (T7).
//
// An included build (`catalogue/settings.gradle.kts`'s `pluginManagement.includeBuild`),
// the same relationship build-logic/ has to platform-build — but with one consumer
// (`gst-catalogue`, in this same repository) rather than every Kotlin repository, so
// unlike build-logic it is never published: no `maven-publish` here.
//
// `kotlin-dsl` compiles gst.catalogue-generator.gradle.kts as a precompiled script
// plugin and the gst/catalogue/*.kt sources it imports as plain Kotlin, using the
// Kotlin version embedded in this Gradle distribution (STK-04) — declaring a separate
// `org.jetbrains.kotlin.jvm` version alongside `kotlin-dsl` is unsupported and unneeded.

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Classpath for gst.catalogue-generator.gradle.kts's own `plugins { id("org.jetbrains.kotlin.jvm") }`
    // (no version there — it resolves from this classpath, same technique
    // build-logic/build.gradle.kts uses for gst.kotlin-base.gradle.kts): the precompiled
    // script plugin needs the kotlin.jvm plugin's own type-safe accessors (sourceSets,
    // tasks.named("compileKotlin")) at compile time, even though the *version* actually
    // governing gst-catalogue's compilation is the one its own build.gradle.kts applies.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") // STK-01 pin, mirrored

    // STK-01 pin, mirrored from platform-build/gradle/libs.versions.toml (snakeyaml =
    // "2.7"): this build has no shared version catalogue to import (single-repository,
    // single-consumer tool, unlike build-logic), so the pin is a literal here instead.
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC")
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
}
