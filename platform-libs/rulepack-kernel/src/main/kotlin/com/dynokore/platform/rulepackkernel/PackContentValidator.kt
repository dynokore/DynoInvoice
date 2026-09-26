package com.dynokore.platform.rulepackkernel

/**
 * Refuses a pack whose declarative content (INV-BR-214: "rules are versioned
 * data, not code") carries an executable payload — a rules DSL or embedded
 * scripting engine is deliberately not used (INV-REQ-2393): "an execution
 * engine inside the pack would make packs unreviewable by the tax approver who
 * must sign them". Called on [PackArtefact.contents] after [PackVerifier]
 * has verified the artefact's authenticity — checking a signed artefact's
 * *shape* is a separate concern from checking who signed it.
 *
 * Framework-free (ARC-16): the `-api` half. `JacksonPackContentValidator`
 * (`-impl`) does the actual JSON parsing (STK-08: Jackson 3).
 */
fun interface PackContentValidator {
    fun validate(contents: ByteArray): ContentValidationOutcome
}
