// build.gradle.kts — INV-T-2850 follow-up.
//
// One task the CI job (and a local run) invokes: compile every generated module.
// The modules themselves (one per service x {server, client} plus one per event
// schema) are declared dynamically in settings.gradle.kts; this just aggregates
// their compileKotlin tasks so nothing here needs updating as services/events change.

tasks.register("compileAllContracts") {
    group = "verification"
    description =
        "Generates and compiles every service's contract-server, contract-client and " +
            "event-types Kotlin (INV-T-2850 follow-up; T9-T14b's ad-hoc proof, made permanent)."
    dependsOn(subprojects.map { "${it.path}:compileKotlin" })
}
