package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.BlankText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

/**
 * PostgreSQL reads [BlankText]'s patterns exactly as the Kotlin checks do (INV-Q-4614 (A), INV-Q-4622 (A)): for every
 * code point PostgreSQL text can hold (U+0001-U+10FFFF but the surrogates), alone and between two letters, `~` agrees
 * with [BlankText.isBlank] and [BlankText.isActor].
 *
 * Moved here from tenancy-kernel (coordinator ruling INV-Q-5208): tenancy-kernel can build no DSLContext, and the
 * verdicts are read through [TenancyProbe], in 17 ranges of at most 65,536 code points (`generate_series(?, ?)`).
 */
@Tag("integration")
class BlankTextPostgresIT {
    @ParameterizedTest(name = "{0}")
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `the SQL predicates agree with the Kotlin checks on every code point`(image: String) {
        val rows = verdicts(Databases.of(image).probe)
        val disagreements =
            rows.filter { v ->
                val text = String(Character.toChars(v.codePoint))
                v.blank != BlankText.isBlank(text) || v.wrappedNotActor == BlankText.isActor("a${text}b") ||
                    v.aloneNotActor == BlankText.isActor(text)
            }
        assertEquals(emptyList<String>(), disagreements.map { "U+%04X".format(it.codePoint) })
        assertEquals(CODE_POINTS, rows.size, "every code point but the surrogates was compared")
    }

    /** PostgreSQL's verdict per code point: blank alone, not an actor between two letters, not an actor alone. */
    private data class Verdict(
        val codePoint: Int,
        val blank: Boolean,
        val wrappedNotActor: Boolean,
        val aloneNotActor: Boolean,
    )

    private fun verdicts(probe: TenancyProbe): List<Verdict> =
        (1..MAX_CODE_POINT step RANGE).flatMap { from ->
            probe
                .fetchRows(
                    "select cp, chr(cp) ~ ? as blank, ('a' || chr(cp) || 'b') ~ ? as wrapped_not_actor, " +
                        "chr(cp) ~ ? as alone_not_actor from generate_series(?, ?) cp " +
                        "where cp not between 55296 and 57343 order by cp",
                    BlankText.SQL_BLANK,
                    BlankText.SQL_NOT_ACTOR,
                    BlankText.SQL_NOT_ACTOR,
                    from,
                    minOf(from + RANGE - 1, MAX_CODE_POINT),
                ).map {
                    Verdict(
                        (it.getValue("cp") as Number).toInt(),
                        it.getValue("blank") as Boolean,
                        it.getValue("wrapped_not_actor") as Boolean,
                        it.getValue("alone_not_actor") as Boolean,
                    )
                }
        }

    private companion object {
        const val RANGE = 65_536
        const val MAX_CODE_POINT = 1_114_111
        const val SURROGATES = 2_048
        const val CODE_POINTS = MAX_CODE_POINT - SURROGATES
    }
}
