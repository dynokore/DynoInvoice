package com.dynokore.gst.controlPlane.acceptance.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import java.io.File

internal const val SERVICE_PACKAGE = "com.dynokore.gst.controlPlane"

internal object Repository {
    val root: File = File(System.getProperty("gst.repo.root"))

    private fun main(module: String) =
        "$module/src/main/kotlin".takeIf {
            File(root, "$module/src/main/kotlin").isDirectory
        }

    /** The production sources of the four hand-written modules (generated contract and jOOQ code are not scanned). */
    fun production(): KoScope =
        listOf("control-plane-domain", "control-plane-application", "control-plane-adapters", "control-plane-bootstrap")
            .mapNotNull(::main)
            .map { Konsist.scopeFromDirectory(it) }
            .reduce { a, b -> a + b }

    /** The layered view: the hand-written modules and the generated contract sources (the ARC-10 `contract` layer). */
    fun layered(): KoScope =
        production() +
            Konsist.scopeFromDirectory("control-plane-contract/build/generated/contract-server/src/main/kotlin")

    fun adaptersDir(): File = File(root, "control-plane-adapters")
}
