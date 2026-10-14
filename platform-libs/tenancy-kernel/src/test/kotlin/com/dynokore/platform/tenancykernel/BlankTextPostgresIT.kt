package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * PostgreSQL reads [BlankText]'s patterns exactly as the Kotlin checks do (INV-Q-4614 (A), INV-Q-4622 (A)): for every
 * code point PostgreSQL text can hold (U+0001-U+10FFFF but the surrogates), alone and between two letters, `~` agrees
 * with [BlankText.isBlank] and [BlankText.isActor].
 */
@Tag("integration")
class BlankTextPostgresIT {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the SQL predicates agree with the Kotlin checks on every code point`(image: String) {
        val rows = PostgresProbeFixture(image).use { verdicts(it) }
        val disagreements =
            rows.filter { v ->
                val text = String(Character.toChars(v.codePoint))
                v.blank != BlankText.isBlank(text) || v.wrappedNotActor == BlankText.isActor("a${text}b") ||
                    v.aloneNotActor == BlankText.isActor(text)
            }
        assertEquals(emptyList<String>(), disagreements.map { "U+%04X".format(it.codePoint) })
    }

    /** PostgreSQL's verdict per code point: blank alone, not an actor between two letters, not an actor alone. */
    private data class Verdict(
        val codePoint: Int,
        val blank: Boolean,
        val wrappedNotActor: Boolean,
        val aloneNotActor: Boolean,
    )

    private fun verdicts(fixture: PostgresProbeFixture): List<Verdict> =
        fixture.ownerDataSource().connection.use { c ->
            c
                .prepareStatement(
                    "select cp, chr(cp) ~ ?, ('a' || chr(cp) || 'b') ~ ?, chr(cp) ~ ? " +
                        "from generate_series(1, 1114111) cp where cp not between 55296 and 57343 order by cp",
                ).use { s ->
                    s.setString(1, BlankText.SQL_BLANK)
                    s.setString(2, BlankText.SQL_NOT_ACTOR)
                    s.setString(3, BlankText.SQL_NOT_ACTOR)
                    s.executeQuery().use { rows ->
                        generateSequence {
                            if (rows.next()) {
                                Verdict(rows.getInt(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4))
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }
}
