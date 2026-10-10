package com.dynokore.platform.rulepackkernel.fence

import com.dynokore.platform.rulepackkernel.PackManifest

sealed interface CompileOutcome<out C> {
    data class Compiled<C>(val compiled: C) : CompileOutcome<C>

    data class Failed(val reason: String) : CompileOutcome<Nothing>
}

/**
 * Turns a verified pack's contents into whatever the holding service evaluates with
 * (a compiled Schematron, a code catalogue). Supplied by the service: this module does
 * not know any holder's engine.
 */
fun interface PackCompiler<out C : Any> {
    fun compile(
        manifest: PackManifest,
        contents: ByteArray,
    ): CompileOutcome<C>
}
