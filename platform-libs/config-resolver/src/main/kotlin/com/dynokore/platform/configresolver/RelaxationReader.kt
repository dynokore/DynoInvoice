package com.dynokore.platform.configresolver

import java.time.LocalDate

/**
 * Read port for the acknowledgements behind the value in force of a variation point (INV-T-24432, INV-AC-24431).
 * The module that owns `config_relaxation_acknowledgement` owns the read; a service reads it behind its own port.
 */
interface RelaxationReader {
    /**
     * The acknowledgements of the relaxation that backs the value in force of [key] at [scope] on [at].
     *
     * The value in force is the one [PolicyReader.resolve] gives for the same arguments (same precedence; the
     * exceptions [UnknownVariationPoint], [VariationPointRetired] and [ElectionRequired] propagate). When its source
     * is an [PolicySource.Override] or a [PolicySource.Profile] and the stored setting row it names has a relaxation
     * reference, the result holds every acknowledgement recorded for that relaxation, in the order recorded.
     * Otherwise (catalogue default, or no relaxation) it is empty.
     *
     * @throws IllegalArgumentException when [scope] has no tenant (installation scope).
     */
    fun acknowledgementsInForce(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
    ): List<RelaxationAcknowledgement>
}
