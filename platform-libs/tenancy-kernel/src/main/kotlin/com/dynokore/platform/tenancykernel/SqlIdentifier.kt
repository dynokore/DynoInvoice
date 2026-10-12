package com.dynokore.platform.tenancykernel

/**
 * The one validated identifier type of the platform (INV-REQ-854 b5, INV-ADR-3968 item 11; coordinator ruling
 * INV-Q-4036): an identifier (a schema, table, column or policy name) enters SQL text only as the result of
 * [require]. There is no bind parameter for an identifier, so this is the only gate between a name and the SQL it
 * is spliced into. Values never go through here: they are bind parameters.
 *
 * platform-build's `SqlGuards` (0.9.0) accepts exactly this function as identifier-safe; a module-local copy, a
 * regex `require` check or an `identifier()` helper is not accepted. A list of identifiers is validated element
 * by element.
 */
object SqlIdentifier {
    private val PLAIN = Regex("^[a-z][a-z0-9_]{0,62}$")

    /**
     * Returns [name] when it is a plain lower-case identifier of at most 63 characters (PostgreSQL's
     * NAMEDATALEN - 1, so a longer name is never silently truncated); throws [IllegalArgumentException]
     * otherwise. [what] names the identifier in the error message (for example "schema").
     */
    fun require(
        name: String,
        what: String,
    ): String {
        require(PLAIN.matches(name)) { "$what must be a plain lower-case identifier of at most 63 chars, was '$name'" }
        return name
    }
}
