// crypto/build.gradle.kts — platform-libs L1 module `crypto` (CS-53; INV-Q-3356 M-A; INV-T-3358 / FU-E2).
//
// The one place where the platform hashes, MACs, draws secure random tokens and signs or verifies
// detached CMS signatures (STK-13), each under a named algorithm: callers name an algorithm, they
// never configure cryptography themselves (CS-53). The lowest L1 module: it uses no other module.
//
// One Gradle project, one version, one changelog for both halves of its api/impl split:
//   - `src/main` (published as `crypto-api`) — hashing, HMAC, SecureRandom tokens, constant-time
//     comparison (pure JDK), and the detached-signature ports and value types. Framework-free and
//     free of Bouncy Castle (ARC-16), so another module's own `-api` half may depend on it
//     (evidence-store's ContentHash does).
//   - `src/impl` (published as `crypto`) — `BouncyCastleCms`, the detached CMS signer and verifier,
//     and `JcaSigningKey`.

import gst.build.Layer

plugins {
    id("gst.library")
    `maven-publish`
}

gstLibrary {
    layer = Layer.L1
}

version = file("version.txt").readText().trim()

sourceSets {
    create("impl") {
        kotlin.srcDir("src/impl/kotlin")
        resources.srcDir("src/impl/resources")
    }
}

dependencies {
    add("implImplementation", sourceSets["main"].output)
    // STK-13: detached CMS, ECDSA P-256 with SHA-256.
    add("implImplementation", libs.bouncycastle.bcprov)
    add("implImplementation", libs.bouncycastle.bcpkix)

    testImplementation(sourceSets["impl"].output)
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, matched with platform-build's own modules
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
}

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        // A separate output directory, so this jar never overwrites the `-api` jar of the same base
        // name and version under build/libs/ (the collision tenancy-kernel's and evidence-store's
        // own `implJar` fix): rulepack-kernel consumes this task's output directly.
        destinationDirectory.set(layout.buildDirectory.dir("libs-impl"))
        from(sourceSets["impl"].output)
    }

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "${project.name}-api"
            from(components["java"]) // `main` only
        }
        create<MavenPublication>("impl") {
            artifactId = project.name
            artifact(implJar)
        }
    }
}

// Two Jar tasks publish from this one project (`jar` for `api`, `implJar` for `impl`); every
// publish-related task's own validation otherwise flags an undeclared dependency on whichever Jar
// task is not its own.
tasks.matching { it.name.startsWith("generateMetadataFileFor") || it.name.startsWith("publish") }.configureEach {
    dependsOn(implJar, tasks.named("jar"))
}
