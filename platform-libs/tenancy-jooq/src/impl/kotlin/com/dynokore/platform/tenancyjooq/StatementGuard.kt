package com.dynokore.platform.tenancyjooq

import org.jooq.Query
import org.jooq.QueryPart
import org.jooq.SQL
import org.jooq.Select
import org.jooq.impl.QOM

/**
 * The refusals both the injector and the execute-time check apply to every query part they visit, deny by default:
 * plain SQL (`DSL.sql`, `field(String)`, `table(String)`, `condition(String)`, `resultQuery(String)`, `query(String)`
 * all render an [SQL] part), and every statement that is not a SELECT, INSERT, UPDATE, DELETE or MERGE (TRUNCATE,
 * DDL, anything else).
 *
 * jOOQ itself renders a few operator tokens as [SQL] parts (the `*` of `count(*)`, the `+` of `a + b`); a part whose
 * text is only operator characters names no table, column or value and is let through. Any letter, digit, quote,
 * placeholder or bracket makes it plain SQL.
 */
internal object StatementGuard {
    private val OPERATOR_TOKEN = Regex("""\s*[*+\-/%|&^~<>=!]{1,3}\s*""")

    fun refuseUnsupported(part: QueryPart) {
        if (part is SQL && !OPERATOR_TOKEN.matches(part.toString())) {
            throw TenantPredicateViolation("a plain-SQL query part is refused: $part")
        }
        if (part is Query && !isSupportedStatement(part)) {
            // The statement is not rendered into the message: an attached query renders through these listeners.
            throw TenantPredicateViolation(
                "only SELECT, INSERT, UPDATE, DELETE and MERGE run on a tenancy-jooq DSLContext " +
                    "(TRUNCATE, DDL and every other statement are refused)",
            )
        }
    }

    private fun isSupportedStatement(part: Query): Boolean = part is Select<*> ||
        part is QOM.Insert<*> ||
        part is QOM.Update<*> ||
        part is QOM.Delete<*> ||
        part is QOM.Merge<*>
}
