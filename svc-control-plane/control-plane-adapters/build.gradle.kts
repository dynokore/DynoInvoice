// control-plane-adapters: HTTP controllers, persistence (jOOQ only, INV-T-4201), the stage package. Spring, jOOQ and
// Resilience4j are allowed here (ARC-03's exception). The jOOQ code is generated from db/migration (gst.jooq-codegen,
// INV-T-4223): never hand-written.
plugins {
    id("gst.adapters")
    id("gst.jooq-codegen")
}

// The jars that hold BindingKeyMigration: gst.jooq-codegen installs the signed-binding functions from them before the
// first migration runs (INV-Q-7418 A, platform-build INV-T-7430). Build time only.
val bindingKernel: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

gstJooqCodegen {
    schema = "controlplane"
    packageName = "com.dynokore.gst.controlPlane.adapters.persistence.jooq"
    initScripts.from("src/integrationTest/resources/roles.sql")
    bindingSource.from(bindingKernel)
    bindingSysRole = "controlplane_sys"
    bindingRuntimeRole = "controlplane_runtime"
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + configurations["testCompileClasspath"]
        runtimeClasspath += output + compileClasspath + configurations["testRuntimeClasspath"]
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    bindingKernel(platform(libs.platform.bom))
    bindingKernel(libs.platform.tenancyKernel)
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":control-plane-domain"))
    implementation(project(":control-plane-application"))
    implementation(project(":control-plane-contract"))
    implementation(libs.platform.serviceRuntime.api)
    implementation(libs.platform.serviceRuntime)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.crypto.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.jooq)
    implementation(libs.spring.web)
    implementation(libs.spring.context)
    implementation(libs.slf4j.api)
    compileOnly(libs.jakarta.servletApi)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.moduleKotlin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.jsonSchemaValidator)
    testImplementation(libs.snakeyaml)

    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.jooq)
    integrationTestRuntimeOnly(libs.postgresql)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The pinned platform-contracts release, staged by control-plane-contract: tests read their schemas from it, never a copy.
    systemProperty(
        "gst.contracts.dir",
        rootProject.layout.projectDirectory
            .dir(
                "control-plane-contract/build/contracts-repo/platform-contracts/control-plane/${libs.versions.platformContractsBundle.get()}",
            ).asFile.path,
    )
    systemProperty(
        "gst.migrations.dir",
        layout.projectDirectory
            .dir("src/main/resources/db/migration")
            .asFile.path,
    )
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "STK-39: integration tests against PostgreSQL in Testcontainers, as the runtime login (CS-19)."
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        systemProperty("gst.pg.image", System.getProperty("gst.pg.image") ?: "postgres:18")
        systemProperty(
            "gst.sql.dir",
            layout.projectDirectory
                .dir("src/integrationTest/resources")
                .asFile.path,
        )
    }

tasks.named("check") { dependsOn(integrationTest) }
