import gst.build.Layer

// tenancy-jooq (INV-T-4133; INV-ADR-3968 item 4a; owner ruling INV-Q-4113): the framework tenant predicate on jOOQ.
// L1, published as two artefacts in tenancy-kernel's pattern (TPL-01):
//   - `src/main` -> `tenancy-jooq-api`: TableClass, TableClasses, TenancyTables, TenantPredicateViolation (jOOQ OSS
//     3.21, STK-15).
//   - `src/impl` -> `tenancy-jooq`: TenancyJooq (the VisitListener, the ExecuteListener and the ConnectionProvider
//     over tenancy-kernel's current transaction connection).
//   - `src/testFixtures` (`java-test-fixtures`, capability `tenancy-jooq-test-fixtures`): TenancyProbe and its Spring
//     test auto-configuration (coordinator ruling INV-Q-4140), and this module's own PostgreSQL harness. The harness's
//     Flyway and tenancy-kernel fixtures are compile-only there, so a consumer of TenancyProbe gets jOOQ, the
//     PostgreSQL driver and SLF4J, not this module's test stack.
plugins {
    id("gst.library")
    id("java-test-fixtures")
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
    // TenancyProbe's Spring test auto-configuration, proved on Spring Boot's own context runner; its own source set
    // so Spring Boot's BOM never reaches the PostgreSQL tests' classpath.
    create("springTest") {
        kotlin.srcDir("src/springTest/kotlin")
    }
}

// tenancy-kernel's `implJar` (TransactionRunnerImpl, CurrentConnection) must be configured before it is referenced.
evaluationDependsOn(":tenancy-kernel")
val tenancyKernelImplJar = project(":tenancy-kernel").tasks.named("implJar")

dependencies {
    api(libs.jooq)

    add("implImplementation", sourceSets["main"].output)
    add("implImplementation", project(":tenancy-kernel"))
    add("implImplementation", files(tenancyKernelImplJar))
    add("implImplementation", libs.jooq)

    testFixturesApi(project(":tenancy-kernel"))
    testFixturesApi(libs.jooq)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.slf4j.api)
    add("testFixturesImplementation", sourceSets["impl"].output)
    testFixturesCompileOnly(files(tenancyKernelImplJar))
    testFixturesCompileOnly(testFixtures(project(":tenancy-kernel")))
    testFixturesCompileOnly(libs.flyway.core)
    testFixturesCompileOnly(platform(libs.springBoot.dependencies))
    testFixturesCompileOnly(libs.springBoot.autoconfigure)
    testFixturesCompileOnly(libs.spring.context)

    testImplementation(sourceSets["impl"].output)
    testImplementation(files(tenancyKernelImplJar))
    testImplementation(testFixtures(project(":tenancy-kernel")))
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.databasePostgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testImplementation(libs.junit.platformTestkit) // the mutation check runs the isolation tests without the injector
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple) // the probes' INFO log and Testcontainers' own logs

    add("springTestImplementation", testFixtures(project(":tenancy-jooq")))
    add("springTestImplementation", platform(libs.springBoot.dependencies))
    add("springTestImplementation", libs.springBoot.test)
    add("springTestImplementation", libs.assertj.core) // ApplicationContextRunner's context type extends AssertJ's
    add("springTestImplementation", libs.springBoot.autoconfigure)
    add("springTestImplementation", libs.springBoot.starterJdbc)
    add("springTestRuntimeOnly", libs.postgresql)
    add("springTestImplementation", platform(libs.junit.bom))
    add("springTestImplementation", libs.junit.jupiter)
    add("springTestRuntimeOnly", libs.junit.platformLauncher)
    constraints {
        // Spring Boot's BOM manages jOOQ and the PostgreSQL driver too; the fixtures are built on this repository's.
        add("springTestImplementation", libs.jooq) { version { strictly(libs.versions.jooq.get()) } }
        add("springTestRuntimeOnly", libs.postgresql) { version { strictly(libs.versions.postgresql.get()) } }
    }
}

val springTestTask =
    tasks.register<Test>("springTest") {
        group = "verification"
        description = "Runs TenancyProbe's Spring test auto-configuration tests."
        testClassesDirs = sourceSets["springTest"].output.classesDirs
        classpath = sourceSets["springTest"].runtimeClasspath
        useJUnitPlatform()
        jvmArgs("-Duser.timezone=UTC")
        systemProperty("user.language", "de")
        systemProperty("user.country", "DE")
    }

tasks.named("check") {
    dependsOn(springTestTask)
}

val implJar =
    tasks.register<Jar>("implJar") {
        archiveBaseName.set(project.name)
        destinationDirectory.set(layout.buildDirectory.dir("libs-impl"))
        from(sourceSets["impl"].output)
    }

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC") // CS-41 baseline, matched with platform-build's own modules
    systemProperty("user.language", "de")
    systemProperty("user.country", "DE")
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    // TenancyProbeTest reads the main and impl jars the way `unzip -l` lists them (no TenancyProbe class in either).
    val jars = files(tasks.named("jar"), implJar)
    dependsOn(jars)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-DtenancyJooq.jars=${jars.asPath}") }
    testLogging { showStandardStreams = true }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "${project.name}-api"
            from(components["java"]) // `main`, plus the `test-fixtures` variant in the module metadata
        }
        create<MavenPublication>("impl") {
            artifactId = project.name
            artifact(implJar)
        }
    }
}

tasks.matching { it.name.startsWith("generateMetadataFileFor") || it.name.startsWith("publish") }.configureEach {
    dependsOn(implJar, tasks.named("jar"))
}
