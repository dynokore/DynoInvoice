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

// The impl compilation sees main's `internal` members (TenancyTables.library and origin) without making them public.
kotlin {
    target {
        compilations.getByName("impl").associateWith(compilations.getByName("main"))
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
    // FatJarTest's probe, which runs TenancyJooq inside a Spring Boot executable jar (review of !61)
    add("springTestImplementation", sourceSets["impl"].output)
    add("springTestImplementation", files(tenancyKernelImplJar))
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
        // Coordinator ruling INV-Q-4643 (A), delegated (owner 2026-10-14; INV-T-4645): jOOQ's io.r2dbc:r2dbc-spi brings
        // reactive-streams 1.0.3 (CC0-1.0, not in STK-66); 1.0.4 (MIT-0) has the same classes. `api` and
        // `testFixturesApi` put the constraint in tenancy-jooq-api's module metadata (main and test-fixtures variants;
        // the impl artefact depends on tenancy-jooq-api), `implImplementation` pins the impl classpath. Not a dependency.
        api(libs.reactiveStreams)
        add("implImplementation", libs.reactiveStreams)
        testFixturesApi(libs.reactiveStreams)
        // Spring Boot's BOM manages jOOQ and the PostgreSQL driver too; the fixtures are built on this repository's.
        add("springTestImplementation", libs.jooq) { version { strictly(libs.versions.jooq.get()) } }
        add("springTestRuntimeOnly", libs.postgresql) { version { strictly(libs.versions.postgresql.get()) } }
    }
}

// Spring Boot's launcher and the probe's runtime jars, from which FatJarTest builds a real Spring Boot executable jar.
val bootLoader by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    bootLoader(platform(libs.springBoot.dependencies))
    bootLoader(libs.springBoot.loader)
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
        testLogging { events("passed", "skipped", "failed") }
        val probeLibs =
            files(
                tasks.named("jar"),
                tasks.named("implJar"),
                tenancyKernelImplJar,
                configurations["implRuntimeClasspath"],
            ).filter { it.name.endsWith(".jar") }
        val probeClasses = sourceSets["springTest"].output.classesDirs
        dependsOn(probeLibs, bootLoader)
        jvmArgumentProviders +=
            CommandLineArgumentProvider {
                listOf(
                    "-DfatJar.libs=${probeLibs.asPath}",
                    "-DfatJar.classes=${probeClasses.asPath}",
                    "-DfatJar.loader=${bootLoader.asPath}",
                )
            }
    }

tasks.named("check") {
    dependsOn(springTestTask)
}

// CI's unit-test job runs `test` (and `implTest`), never `check`: `test` runs springTest too, so CI runs it on every
// pipeline that tests this module (review of !61), and springTest logs each test so the CI log shows it.
tasks.test {
    dependsOn(springTestTask)
}

// tenancy-jooq's own harness (TenancyJooqFixture, MockExecution) stays in the test fixtures, whose classes the SQL
// placement policy exempts (owner ruling INV-Q-4196), but it is internal and left out of the published test-fixtures
// jar: consumers' tests get TenancyProbe and its Spring auto-configuration only (review of !61). This module's tests
// read the harness from the test fixtures' class directories.
val harnessClasses = listOf("**/TenancyJooqFixture*.class", "**/MockExecution*.class")
tasks.named<Jar>("testFixturesJar") {
    exclude(harnessClasses)
}
dependencies {
    testRuntimeOnly(sourceSets["testFixtures"].output)
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
    val fixturesJar = tasks.named<Jar>("testFixturesJar")
    dependsOn(fixturesJar)
    jvmArgumentProviders +=
        CommandLineArgumentProvider {
            listOf("-DtenancyJooq.testFixturesJar=${fixturesJar.get().archiveFile.get().asFile}")
        }
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
