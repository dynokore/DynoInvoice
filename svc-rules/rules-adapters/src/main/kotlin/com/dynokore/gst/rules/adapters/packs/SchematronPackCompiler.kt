package com.dynokore.gst.rules.adapters.packs

import com.dynokore.platform.integrationcanon.schematron.CompilationListener
import com.dynokore.platform.integrationcanon.schematron.CompiledSchematron
import com.dynokore.platform.integrationcanon.schematron.SchematronRefusedException
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.fence.CompileOutcome
import com.dynokore.platform.rulepackkernel.fence.PackCompiler

/**
 * The stylesheets of a Schematron pack, each compiled once, held in memory (INV-REQ-776). [stylesheets] is immutable
 * and maps the file name to its executable; validation only calls [CompiledSchematron.validate].
 */
class CompiledPackSchematron(
    stylesheets: Map<String, CompiledSchematron>,
) {
    val stylesheets: Map<String, CompiledSchematron> = java.util.Collections.unmodifiableMap(LinkedHashMap(stylesheets))
}

/**
 * The PackCompiler hook of a SCHEMATRON pack (INV-AC-20472): the files whose name ends with `.xsl` or `.xslt` (lower
 * case, suffix equality) are stylesheets and are compiled once with one [SchematronRunner]; other files (a `.sch`)
 * stay in the pack, uncompiled. A refused stylesheet, a pack without a stylesheet or a contents failure (including a
 * `sha256` mismatch) is a [CompileOutcome.Failed] naming the file, with no partial result. The call at activation
 * belongs to the activation fence.
 */
class SchematronPackCompiler(
    listener: CompilationListener,
) : PackCompiler<CompiledPackSchematron> {
    private val runner = SchematronRunner(listener)

    override fun compile(
        manifest: PackManifest,
        contents: ByteArray,
    ): CompileOutcome<CompiledPackSchematron> =
        try {
            CompileOutcome.Compiled(CompiledPackSchematron(compileAll(SchematronPackContents.decode(contents))))
        } catch (e: PackContentsFailure) {
            CompileOutcome.Failed(e.message ?: "contents refused")
        } catch (e: SchematronRefusedException) {
            CompileOutcome.Failed("stylesheet refused: ${e.message}")
        } catch (e: NoStylesheetException) {
            CompileOutcome.Failed(e.message)
        }

    private fun compileAll(decoded: SchematronContents): Map<String, CompiledSchematron> {
        val sheets = decoded.files.filter { it.name.endsWith(".xsl") || it.name.endsWith(".xslt") }
        if (sheets.isEmpty()) throw NoStylesheetException("the pack holds no stylesheet file (.xsl or .xslt)")
        return sheets.associate { sheet ->
            sheet.name to
                try {
                    runner.compile(sheet.content)
                } catch (e: SchematronRefusedException) {
                    throw SchematronRefusedException("'${sheet.name}': ${e.message}", e)
                }
        }
    }

    private class NoStylesheetException(
        override val message: String,
    ) : RuntimeException(message)
}
