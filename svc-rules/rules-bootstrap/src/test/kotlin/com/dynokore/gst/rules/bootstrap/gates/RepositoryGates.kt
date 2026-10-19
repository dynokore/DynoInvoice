package com.dynokore.gst.rules.bootstrap.gates

import java.io.File

/** The files the two gates read, from the repository root Gradle passes in (`gst.repo.root`). */
internal object RepositoryGates {
    val root: File =
        File(
            checkNotNull(
                System.getProperty("gst.repo.root"),
            ) { "gst.repo.root is not set: run the gates through Gradle" },
        )

    fun read(name: String): String? = File(root, name).takeIf { it.isFile }?.readText()

    /** Kotlin files of the test source sets of every module. */
    fun testSources(): Map<String, String> =
        sources { path -> Regex("""/src/(test|[a-zA-Z]*Test|integrationTest)/""").containsMatchIn(path) }

    fun adapterSources(): Map<String, String> =
        sources { path -> path.startsWith("rules-adapters/") && path.contains("/src/main/") }

    private fun sources(accept: (String) -> Boolean): Map<String, String> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter(accept)
            .associateWith { File(root, it).readText() }
}
