// Root build: aggregate entry points only. The modules carry their own plugins (TPL-01).
plugins {
    base
}

// `./gradlew acKeyCoverage` and `./gradlew operationsGate` run the repository gates of the acceptance project.
tasks.register("acKeyCoverage") { dependsOn(":acceptance:acKeyCoverage") }
tasks.register("operationsGate") { dependsOn(":acceptance:operationsGate") }
