// acceptance: a test-only project (TPL-01; not one of the five architectural modules of ARC-10). It holds the two
// repository gates, operations.tsv (contract coverage) and ac-keys.tsv (acceptance-criterion coverage).
plugins {
    id("gst.kotlin-base")
    application
}

dependencies {
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.platform.konsistRules)
    testImplementation(libs.konsist)
}

tasks.withType<Test> {
    dependsOn(":control-plane-contract:generateContractServer")
    useJUnitPlatform()
    // The repository root: the tests scan the sources and migrations of the five modules.
    systemProperty("gst.repo.root", rootProject.layout.projectDirectory.asFile.path)
}

application { mainClass.set("com.dynokore.gst.controlPlane.acceptance.gates.GateMain") }

val contractVersion = libs.versions.platformContractsBundle.get()
val repoRoot = rootProject.layout.projectDirectory
val contractDir =
    rootProject.layout.projectDirectory.dir(
        "control-plane-contract/build/contracts-repo/platform-contracts/control-plane/$contractVersion",
    )

val operationsGate =
    tasks.register<JavaExec>("operationsGate") {
        group = "verification"
        description = "INV-T-4984: operations.tsv lists every operationId and channel of the contract (fails closed)."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.dynokore.gst.controlPlane.acceptance.gates.GateMain")
        args(
            "operations",
            "--contract=${contractDir.asFile}",
            "--table=${repoRoot.asFile}",
            "--adapters=${rootProject.layout.projectDirectory.dir("control-plane-adapters/src/main").asFile}",
        )
        inputs.dir(contractDir)
        inputs.dir(rootProject.layout.projectDirectory.dir("control-plane-adapters/src/main"))
        inputs.files(rootProject.layout.projectDirectory.file("operations.tsv")).optional()
        inputs.files(rootProject.layout.projectDirectory.file("done-tasks.txt")).optional()
        inputs.files(rootProject.layout.projectDirectory.file("operation-owners.tsv")).optional()
        outputs.upToDateWhen { false }
        dependsOn(":control-plane-contract:prepareContractServerSpec")
    }

val acKeyCoverage =
    tasks.register<JavaExec>("acKeyCoverage") {
        group = "verification"
        description =
            "INV-T-4984: every wave 2 row of ac-keys.tsv has a test named by its key; no test names a key outside the table."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.dynokore.gst.controlPlane.acceptance.gates.GateMain")
        args("ac-keys", "--root=${repoRoot.asFile}")
        outputs.upToDateWhen { false }
    }

tasks.named("check") { dependsOn(operationsGate, acKeyCoverage) }
