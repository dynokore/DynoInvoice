package com.dynokore.platform.tenancykernel

/**
 * The one definition of a blank value and of a valid actor value, shared by every module's Kotlin checks and SQL
 * (coordinator rulings INV-Q-4614 (A) and INV-Q-4622 (A), INV-REQ-854, INV-REQ-1189). No module repeats the set.
 *
 * A value is blank when it is NULL, empty or made only of these code points: U+0001-U+001F and U+007F-U+009F (the ISO
 * control characters: tab, newline, CR, NEL and the rest), U+0020, U+00A0, U+1680, U+2000-U+200A, U+2028, U+2029,
 * U+202F, U+205F, U+3000 and U+FEFF (the caseId whitespace set of coordinator ruling INV-Q-4534). `btrim` (spaces
 * only), Kotlin's `isBlank()` (misses U+FEFF and most controls) and locale-dependent classes are not used.
 *
 * An actor (the end user's sub or the service's workload principal) is an identity, never prose: it is not blank and
 * holds no ISO control character anywhere (U+0001-U+001F, U+007F-U+009F; Kotlin also refuses U+0000, which PostgreSQL
 * text cannot hold). Reasons, subjects, keys and other free text keep the blank rule only.
 *
 * Each rule is one regular expression that PostgreSQL (`~`, server encoding UTF8) and `java.util.regex` read the same
 * way, so the SQL predicate and the Kotlin check share one source. They are `const val`s so that DDL text can splice
 * them in where platform-build's SQL guards accept only constants:
 * - blank: `<expr> IS NULL OR <expr> ~ '[SQL_BLANK]'` (a CHECK on a NOT NULL column: `<column> !~ '[SQL_BLANK]'`);
 * - not an actor: `<expr> IS NULL OR <expr> ~ '[SQL_NOT_ACTOR]'` (a CHECK: `<column> !~ '[SQL_NOT_ACTOR]'`).
 */
object BlankText {
    /** Matches a blank value: empty, or only the code points of the blank set. */
    const val SQL_BLANK =
        """^[\u0001-\u001F\u007F-\u009F    -     　﻿]*$"""

    /** Matches a value that holds an ISO control character anywhere (U+0001-U+001F, U+007F-U+009F). */
    const val SQL_CONTROL = """[\u0001-\u001F\u007F-\u009F]"""

    /** Matches a value that is not a valid actor: blank, or holding a control character anywhere. */
    const val SQL_NOT_ACTOR = "$SQL_BLANK|$SQL_CONTROL"

    private val blank = Regex(SQL_BLANK)
    private val notActor = Regex(SQL_NOT_ACTOR)

    /** True when [value] is NULL, empty or made only of the blank set's code points. */
    fun isBlank(value: String?): Boolean = value == null || blank.matches(value)

    /**
     * True when [value] is a valid actor: not blank and holding no control character (U+0000-U+001F,
     * U+007F-U+009F) anywhere.
     */
    fun isActor(value: String?): Boolean = value != null && '\u0000' !in value && !notActor.containsMatchIn(value)
}
