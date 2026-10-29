// control-plane-bootstrap: the Spring Boot application and its composition (TPL-18). All wiring lives here.
// The Spring Boot plugin brings tools.jackson.core 3.1.5 (HIGH CVEs, fixed in 3.1.7) onto this module's plugin classpath;
// the constraint lifts both to 3.1.7, as platform-build does for its own classpath. Remove once the plugin resolves 3.1.7+.
buildscript {
    dependencies {
        constraints {
            classpath("tools.jackson.core:jackson-core:3.1.7")
            classpath("tools.jackson.core:jackson-databind:3.1.7")
        }
    }
}

plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

sourceSets {
    create("integrationTest") {
        compileClasspath +=
            sourceSets["main"].output + sourceSets["test"].output + configurations["testCompileClasspath"]
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
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":control-plane-adapters"))
    implementation(project(":control-plane-application"))
    implementation(project(":control-plane-domain"))
    implementation(project(":control-plane-contract"))
    implementation(libs.platform.serviceRuntime)
    implementation(libs.platform.productRuntime)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterFlyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.databasePostgresql)
    implementation(libs.jooq)
    implementation(libs.platform.crypto.api)
    // EntityRef and TenantId, the parameter types of JobCommandAuthorizer
    implementation(libs.platform.outboxAndQueue.api)
    implementation(libs.platform.tenancyKernel.api)
    implementation(libs.platform.productRuntime.api)
    implementation(libs.platform.rulepackKernel.api)
    // the BouncyCastleCms bean of service-runtime's CryptoWiring (DetachedSigner)
    runtimeOnly(libs.platform.crypto)
    // the OutboxWriter and the relay of service-runtime's OutboxAndQueueWiring, and the tables the job runtime writes its events to
    runtimeOnly(libs.platform.outboxAndQueue)
    // the LocalAuditWriter bean of service-runtime's AuditLogWiring (service-runtime.modules.audit.writer: local binds the local audit writer although the service has an OutboxWriter)
    runtimeOnly(libs.platform.auditLog)
    // the platform-scoped tables of config-resolver's ConfigSchema, read by service-runtime's TenancyJooqWiring
    runtimeOnly(libs.platform.configResolver)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
    // AssertableApplicationContext, the result type of ApplicationContextRunner.run, names AssertJ in its supertypes
    testImplementation(libs.assertj.core)
    // AuditWriter and LocalAuditWriter, named by the composition test
    testImplementation(libs.platform.auditLog.api)
    testImplementation(libs.platform.auditLog)

    // JobRuntimeIT runs the composition over the harness of control-plane-adapters (INV-Q-10926 A)
    integrationTestImplementation(testFixtures(project(":control-plane-adapters")))
    integrationTestImplementation(libs.jooq)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.platform.productRuntime.api)
    integrationTestImplementation(libs.platform.tenancyKernel)
}

tasks.withType<Test> { useJUnitPlatform() }

val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description =
            "STK-39: the job runtime of the composition against PostgreSQL in Testcontainers, as the runtime login (CS-19)."
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        dependsOn(":control-plane-adapters:harnessInitSql")
        systemProperty("gst.pg.image", System.getProperty("gst.pg.image") ?: "postgres:18")
        systemProperty(
            "gst.sql.dir",
            rootProject.layout.projectDirectory
                .dir("control-plane-adapters/build/harness-init-sql")
                .asFile.path,
        )
        systemProperty(
            "gst.migrations.dir",
            rootProject.layout.projectDirectory
                .dir("control-plane-adapters/src/main/resources/db/migration")
                .asFile.path,
        )
    }

tasks.named("check") { dependsOn(integrationTest) }
