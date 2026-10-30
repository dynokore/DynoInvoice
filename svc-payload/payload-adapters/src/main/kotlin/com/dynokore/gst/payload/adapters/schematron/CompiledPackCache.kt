package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.FailedAssertion
import com.dynokore.gst.payload.domain.SchematronValidator
import com.dynokore.gst.payload.domain.StagedPackMissingException
import com.dynokore.gst.payload.domain.StagedPackSource
import com.dynokore.gst.payload.domain.ValidationOutcome
import com.dynokore.platform.integrationcanon.schematron.CompiledSchematron
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds one [CompiledSchematron] per pack version in memory (INV-REQ-472 behaviors 2 and 3, INV-REQ-1359,
 * INV-REQ-1210).
 * A stylesheet is compiled at [activate], or once on the first miss of [validate] from [source]; never per document.
 * No version is evicted, so two or more versions are held at once. Nothing here reaches the `rules` schema.
 */
class CompiledPackCache(
    private val runner: SchematronRunner,
    private val source: StagedPackSource,
) : SchematronValidator {
    private val held = ConcurrentHashMap<String, CompiledSchematron>()

    /** Compiles [stylesheet] and holds it as [packVersion], replacing a held compilation of the same version. */
    fun activate(
        packVersion: String,
        stylesheet: ByteArray,
    ) {
        held[packVersion] = runner.compile(stylesheet)
    }

    override fun validate(
        packVersion: String,
        document: ByteArray,
    ): ValidationOutcome {
        val compiled = held[packVersion] ?: miss(packVersion)
        return ValidationOutcome(
            compiled.validate(document).failedAsserts.map {
                FailedAssertion(it.id, it.location, it.test, it.role, it.text)
            },
        )
    }

    // computeIfAbsent runs the loader at most once per key; a throw from it holds nothing.
    private fun miss(packVersion: String): CompiledSchematron =
        held.computeIfAbsent(packVersion) {
            val stylesheet = source.fetch(packVersion) ?: throw StagedPackMissingException(packVersion)
            runner.compile(stylesheet)
        }
}
