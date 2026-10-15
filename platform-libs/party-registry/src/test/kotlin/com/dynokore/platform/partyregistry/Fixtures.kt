package com.dynokore.platform.partyregistry

import java.time.Instant

internal object Fixtures {
    val at: Instant = Instant.parse("2026-10-16T00:00:00Z")
    val evidence = EvidenceRef("DOC-1")

    fun declared(by: ProvidedBy = ProvidedBy.COUNTERPARTY) = Provenance.declared(by, evidence, at)

    fun uen(value: String = "201912345K") = Identifier(IdentifierScheme.UEN, value, declared())

    /** A stub hook: the owner of a format supplies the real one. Accepts a value iff it starts with `OK`. */
    val hook =
        SchemeValidator { _, value ->
            if (value.startsWith("OK")) FormatVerdict.Valid else FormatVerdict.Invalid("bad format")
        }
}
