package com.dynokore.gst.evidence.adapters

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.sql.Connection

/**
 * The schema names that the foreign keys, views, functions and triggers of one schema refer to (INV-REQ-636,
 * INV-ADR-269, INV-AC-7871), read from the catalogue through jOOQ: pg_constraint, pg_depend, pg_trigger and pg_proc,
 * with the catalogues pg_depend points into (pg_class, pg_type, pg_rewrite, ...) to place each object in a schema.
 *
 * Deny by default (P140): a reference to an object this file cannot place in a schema is a finding named
 * `unresolved:...`, unless its catalogue class is in [SCHEMALESS] (a reviewed list, one line each). Schema names are
 * compared by equality, never by prefix, so `evidence_x` is not `evidence`.
 */
object SchemaReferences {
    /** A catalogue class whose objects belong to no schema; each entry has its justification. */
    private val SCHEMALESS =
        mapOf(
            CLASS_LANGUAGE to "a procedural language (plpgsql) is database-wide, not an object of any schema",
        )

    /** One reference of [from] (an object of the schema, created by role [owner]) to an object of [toSchema]. */
    data class Reference(
        val kind: String,
        val from: String,
        val toSchema: String,
        val owner: String,
    )

    /** An object of a catalogue with a schema and an owner; [extra] is the function body of a pg_proc row. */
    private class Obj(
        val id: Long,
        val schema: Long,
        val name: String,
        val owner: Long,
        val extra: String = "",
    )

    /** An object of the schema: what a finding calls it, and the role that created it. */
    private class Owned(
        val label: String,
        val owner: String,
    )

    private class Row(
        private val record: Record,
    ) {
        fun long(index: Int): Long = record.get(index, Long::class.java)

        fun string(index: Int): String = record.get(index, String::class.java).orEmpty()
    }

    private fun oid(name: String): Field<Long> = DSL.field(DSL.name(name), SQLDataType.BIGINT)

    private fun text(name: String): Field<String> = DSL.field(DSL.name(name), SQLDataType.VARCHAR)

    private fun fetch(
        dsl: DSLContext,
        table: String,
        vararg fields: Field<*>,
    ): List<Row> =
        dsl
            .select(*fields)
            .from(DSL.table(DSL.name("pg_catalog", table)))
            .fetch()
            .map { Row(it) }

    private fun objects(
        dsl: DSLContext,
        table: String,
        prefix: String,
        extra: String? = null,
    ): List<Obj> {
        val fields = listOf(oid("oid"), oid("${prefix}namespace"), text("${prefix}name"), oid("${prefix}owner"))
        return fetch(dsl, table, *(fields + listOfNotNull(extra?.let { text(it) })).toTypedArray()).map {
            Obj(it.long(0), it.long(1), it.string(2), it.long(3), if (extra != null) it.string(4) else "")
        }
    }

    private class Constraint(
        val id: Long,
        val schema: Long,
        val relation: Long,
        val referenced: Long,
        val type: String,
        val name: String,
    )

    /** A pg_depend row: [from] and [to] are (catalogue class oid, object oid). */
    private class Dependency(
        val from: Pair<Long, Long>,
        val to: Pair<Long, Long>,
    )

    private class Trigger(
        val id: Long,
        val relation: Long,
        val function: Long,
        val constrained: Long,
        val name: String,
    )

    /** The catalogue rows the three kinds of reference are read from. */
    private class Catalogue(
        dsl: DSLContext,
        val schema: String,
    ) {
        val roles = fetch(dsl, "pg_roles", oid("oid"), text("rolname")).associate { it.long(0) to it.string(1) }
        val namespaces =
            fetch(dsl, "pg_namespace", oid("oid"), text("nspname")).associate { it.long(0) to it.string(1) }
        val schemaOid = namespaces.entries.single { it.value == schema }.key
        val classes = objects(dsl, "pg_class", "rel")
        val procs = objects(dsl, "pg_proc", "pro", "prosrc").filter { it.schema == schemaOid }
        val classesIn = classes.filter { it.schema == schemaOid }.associateBy { it.id }
        val constraints =
            fetch(
                dsl,
                "pg_constraint",
                oid("oid"),
                oid("connamespace"),
                oid("conrelid"),
                oid("confrelid"),
                text("contype"),
                text("conname"),
            ).map { Constraint(it.long(0), it.long(1), it.long(2), it.long(3), it.string(4), it.string(5)) }
        val triggers =
            fetch(dsl, "pg_trigger", oid("oid"), oid("tgrelid"), oid("tgfoid"), oid("tgconstrrelid"), text("tgname"))
                .map { Trigger(it.long(0), it.long(1), it.long(2), it.long(3), it.string(4)) }
        val rewrites = fetch(dsl, "pg_rewrite", oid("oid"), oid("ev_class")).map { it.long(0) to it.long(1) }
        val attrdefs = fetch(dsl, "pg_attrdef", oid("oid"), oid("adrelid")).map { it.long(0) to it.long(1) }
        val policies = fetch(dsl, "pg_policy", oid("oid"), oid("polrelid")).map { it.long(0) to it.long(1) }
        val depends =
            fetch(dsl, "pg_depend", oid("classid"), oid("objid"), oid("refclassid"), oid("refobjid"))
                .map { Dependency(it.long(0) to it.long(1), it.long(2) to it.long(3)) }

        /** Where each referable object lives: (catalogue class oid, object oid) to its schema oid. */
        val schemaOf: Map<Pair<Long, Long>, Long> =
            buildMap {
                classes.forEach { put(CLASS_CLASS to it.id, it.schema) }
                objects(dsl, "pg_proc", "pro").forEach { put(CLASS_PROC to it.id, it.schema) }
                objects(dsl, "pg_type", "typ").forEach { put(CLASS_TYPE to it.id, it.schema) }
                objects(dsl, "pg_operator", "opr").forEach { put(CLASS_OPERATOR to it.id, it.schema) }
                objects(dsl, "pg_opclass", "opc").forEach { put(CLASS_OPCLASS to it.id, it.schema) }
                objects(dsl, "pg_opfamily", "opf").forEach { put(CLASS_OPFAMILY to it.id, it.schema) }
                objects(dsl, "pg_collation", "coll").forEach { put(CLASS_COLLATION to it.id, it.schema) }
                constraints.forEach { put(CLASS_CONSTRAINT to it.id, it.schema) }
                namespaces.keys.forEach { put(CLASS_NAMESPACE to it, it) }
            }

        fun placed(target: Pair<Long, Long>): String = schemaOf[target]?.let { namespaces[it] } ?: "unresolved:$target"

        fun roleOf(relation: Long): String = roles.getValue(classesIn.getValue(relation).owner)

        /** The objects of the schema by (catalogue class oid, object oid). */
        val owned: Map<Pair<Long, Long>, Owned> =
            buildMap {
                classesIn.values.forEach { put(CLASS_CLASS to it.id, Owned("relation ${it.name}", roleName(it.owner))) }
                procs.forEach { put(CLASS_PROC to it.id, Owned("function ${it.name}", roleName(it.owner))) }
                constraints.filter { it.relation in classesIn }.forEach {
                    put(CLASS_CONSTRAINT to it.id, Owned("constraint ${it.name}", roleOf(it.relation)))
                }
                triggers.filter { it.relation in classesIn }.forEach {
                    put(CLASS_TRIGGER to it.id, Owned("trigger ${it.name}", roleOf(it.relation)))
                }
                dependentsOf(CLASS_REWRITE, rewrites, "view")
                dependentsOf(CLASS_ATTRDEF, attrdefs, "default of")
                dependentsOf(CLASS_POLICY, policies, "policy of")
            }

        private fun MutableMap<Pair<Long, Long>, Owned>.dependentsOf(
            catalogue: Long,
            rows: List<Pair<Long, Long>>,
            what: String,
        ) = rows.filter { it.second in classesIn }.forEach {
            put(catalogue to it.first, Owned("$what ${classesIn.getValue(it.second).name}", roleOf(it.second)))
        }

        private fun roleName(owner: Long): String = roles.getValue(owner)
    }

    fun references(
        connection: Connection,
        schema: String,
    ): List<Reference> = references(DSL.using(connection, SQLDialect.POSTGRES), schema)

    fun references(
        dsl: DSLContext,
        schema: String,
    ): List<Reference> {
        val catalogue = Catalogue(dsl, schema)
        return (
            dependencies(catalogue) + foreignKeys(catalogue) + triggers(catalogue) + bodies(catalogue)
        ).distinct()
    }

    /** pg_depend: everything an object of the schema depends on (views, defaults, policies, functions, columns). */
    private fun dependencies(c: Catalogue): List<Reference> =
        c.depends.mapNotNull { dependency ->
            val from = c.owned[dependency.from]
            if (from == null || dependency.to.second == 0L || dependency.to.first in SCHEMALESS) {
                null
            } else {
                Reference("depend", from.label, c.placed(dependency.to), from.owner)
            }
        }

    /** pg_constraint: a foreign key points at the table it references. */
    private fun foreignKeys(c: Catalogue): List<Reference> =
        c.constraints.filter { it.type == "f" && it.relation in c.classesIn }.map {
            val to = c.placed(CLASS_CLASS to it.referenced)
            Reference("foreign key", "constraint ${it.name}", to, c.roleOf(it.relation))
        }

    /** pg_trigger: the function a trigger runs and, for a constraint trigger, the table it refers to. */
    private fun triggers(c: Catalogue): List<Reference> =
        c.triggers.filter { it.relation in c.classesIn }.flatMap {
            val from = "trigger ${it.name}"
            val owner = c.roleOf(it.relation)
            listOfNotNull(
                Reference("trigger function", from, c.placed(CLASS_PROC to it.function), owner),
                if (it.constrained != 0L) {
                    Reference("trigger table", from, c.placed(CLASS_CLASS to it.constrained), owner)
                } else {
                    null
                },
            )
        }

    /**
     * pg_proc: pg_depend does not track a function body, so every schema name that qualifies an identifier in the body
     * of a function of the schema is a reference (equality of the name, bare or quoted).
     */
    private fun bodies(c: Catalogue): List<Reference> {
        val schemaNames = c.namespaces.values.toSet()
        return c.procs.flatMap { proc ->
            QUALIFIED
                .findAll(proc.extra)
                .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filter { it in schemaNames }
                .map { Reference("function body", "function ${proc.name}", it, c.roles.getValue(proc.owner)) }
                .toList()
        }
    }

    /** The references that point outside [allowed] schema names; empty for a separable schema. */
    fun outside(
        references: List<Reference>,
        allowed: Set<String>,
    ): List<Reference> = references.filter { it.toSchema !in allowed }

    /** `schema.name` or `"schema".name` in a body: group 1 the bare name, group 2 the quoted one. */
    private val QUALIFIED =
        Regex("""(?<![A-Za-z0-9_$.\"])(?:([A-Za-z_][A-Za-z0-9_$]*)|\"([^\"]+)\")\s*\.\s*[A-Za-z_\"]""")

    private const val CLASS_CLASS = 1259L
    private const val CLASS_PROC = 1255L
    private const val CLASS_TYPE = 1247L
    private const val CLASS_CONSTRAINT = 2606L
    private const val CLASS_TRIGGER = 2620L
    private const val CLASS_REWRITE = 2618L
    private const val CLASS_ATTRDEF = 2604L
    private const val CLASS_POLICY = 3256L
    private const val CLASS_NAMESPACE = 2615L
    private const val CLASS_OPERATOR = 2617L
    private const val CLASS_OPCLASS = 2616L
    private const val CLASS_OPFAMILY = 2753L
    private const val CLASS_COLLATION = 3456L
    private const val CLASS_LANGUAGE = 2612L
}
