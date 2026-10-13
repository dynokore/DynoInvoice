package com.dynokore.platform.tenancyjooq

import org.jooq.Name
import org.jooq.Query
import org.jooq.QueryPart
import org.jooq.SQL
import org.jooq.Select
import org.jooq.VisitContext
import org.jooq.impl.CustomCondition
import org.jooq.impl.CustomField
import org.jooq.impl.CustomQueryPart
import org.jooq.impl.CustomTable
import org.jooq.impl.QOM

/**
 * The refusals both the injector and the execute-time check apply to every query part they visit, deny by default
 * (INV-T-4133; review of !61); the settings, dialect and listeners tenancy-jooq owns are checked when it is
 * configured and before each statement runs ([OwnedSettings], [TenantStatementCheck]):
 * - plain SQL, whatever its text: every [SQL] part (`DSL.sql`, `field(String)`, `table(String)`,
 *   `condition(String)`, `resultQuery(String)`, `query(String)`, every template) except the operator tokens jOOQ
 *   itself renders as [SQL] parts, which are recognised by where they sit, never by their text;
 * - every consumer custom query part (`CustomQueryPart`, `CustomCondition`, `CustomField`, `CustomTable`), which
 *   renders its own SQL text (jOOQ's own OVER clause parts below its window functions are let through);
 * - every name rendered unquoted (`DSL.unquotedName`, `DSL.function(String, ...)`): an unquoted name is SQL text
 *   (jOOQ's own system names are let through while they are single identifiers);
 * - every statement that is not a SELECT, INSERT, UPDATE, DELETE or MERGE (TRUNCATE, DDL, anything else);
 * - a rendered statement that [RenderedSql] refuses (a comment, a separator, an unbalanced fragment), on the
 *   statement's root part once it is rendered.
 */
internal object StatementGuard {
    fun visitStart(context: VisitContext) {
        val part = context.queryPart()
        if (part is SQL && !isJooqOperatorToken(context)) {
            throw TenantPredicateViolation("a plain-SQL query part is refused, whatever its text")
        }
        if (isCustom(part) && !isJooqWindowPart(context)) {
            throw TenantPredicateViolation("a custom query part is refused: it renders its own SQL text")
        }
        if (part is Name && !isRenderedQuotedOrIdentifier(part)) {
            throw TenantPredicateViolation("a name rendered unquoted is refused: it is SQL text")
        }
        if (part is Query && !isSupportedStatement(part)) {
            // The statement is not rendered into the message: an attached query renders through these listeners.
            throw TenantPredicateViolation(
                "only SELECT, INSERT, UPDATE, DELETE and MERGE run on a tenancy-jooq DSLContext " +
                    "(TRUNCATE, DDL and every other statement are refused)",
            )
        }
    }

    fun visitEnd(context: VisitContext) {
        if (context.queryPartsLength() == 1) {
            context.renderContext()?.let { RenderedSql.require(it.render()) }
        }
    }

    private fun isSupportedStatement(part: Query): Boolean = part is Select<*> || part is QOM.Insert<*> || part is QOM.Update<*> || part is QOM.Delete<*> ||
        part is QOM.Merge<*>

    /**
     * Names render quoted, except a name created unquoted (`DSL.unquotedName`, `DSL.function(String, ...)`), which
     * is refused, and the system names jOOQ itself gives its functions and casts (`count`, `cast`), which render
     * unquoted and are let through only while every part is a single identifier: one identifier can name a function
     * or a type but never add syntax.
     */
    private fun isRenderedQuotedOrIdentifier(name: Name): Boolean = when (name.quoted()) {
        Name.Quoted.DEFAULT, Name.Quoted.QUOTED -> true
        Name.Quoted.SYSTEM -> name.parts().all { IDENTIFIER.matches(it.last().orEmpty()) }
        else -> false
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * jOOQ renders the OVER clause of its window functions through its own `CustomQueryPart.of`. Such a part is let
     * through only when its class and its parent's class are loaded from jOOQ's own jar (by code source, so no
     * consumer class can pose as jOOQ's by its name or package) and the parent is a window function, where the typed
     * API places no consumer part.
     */
    private fun isJooqWindowPart(context: VisitContext): Boolean {
        val parts = context.queryParts()
        val parent = parts.getOrNull(parts.size - 2)
        return parent is QOM.WindowFunction<*, *> && FROM_JOOQ.get(parent.javaClass) && FROM_JOOQ.get(context.queryPart().javaClass)
    }

    private fun codeSource(type: Class<*>): String? = type.getResource("/" + type.name.replace('.', '/') + ".class")?.toString()?.substringBefore("!/")

    private val JOOQ_JAR: String? = codeSource(CustomQueryPart::class.java)

    private val FROM_JOOQ =
        object : ClassValue<Boolean>() {
            override fun computeValue(type: Class<*>): Boolean = JOOQ_JAR != null && codeSource(type) == JOOQ_JAR
        }

    private fun isCustom(part: QueryPart): Boolean = part is CustomQueryPart || part is CustomCondition || part is CustomField<*> || part is CustomTable<*>

    /**
     * jOOQ renders the operator of an arithmetic or concatenation node, and the `*` of `count(*)`, as an [SQL] part.
     * An operator node's operands are fields, so an [SQL] part directly below one is jOOQ's own operator token; a
     * plain-SQL operand sits below its own field. `count(*)` is a [QOM.Count] without a field, below which jOOQ
     * alone renders the asterisk's field (`count(field("*"))` has a field and is refused).
     */
    private fun isJooqOperatorToken(context: VisitContext): Boolean {
        val parts = context.queryParts()
        val parent = parts.getOrNull(parts.size - 2)
        return isOperatorNode(parent) || isCountAsterisk(parts)
    }

    private fun isOperatorNode(part: QueryPart?): Boolean = part is QOM.Add<*> || part is QOM.Sub<*> || part is QOM.Mul<*> || part is QOM.Div<*> ||
        part is QOM.BitAnd<*> || part is QOM.BitOr<*> || part is QOM.BitXor<*> || part is QOM.Concat

    private fun isCountAsterisk(parts: Array<QueryPart>): Boolean {
        val count = parts.getOrNull(parts.size - COUNT_DEPTH)
        return count is QOM.Count &&
            count.`$field`() == null &&
            parts[parts.size - 3].javaClass.simpleName == "QueryPartCollectionView" &&
            parts[parts.size - 2].javaClass.simpleName == "SQLField"
    }

    private const val COUNT_DEPTH = 4
}
