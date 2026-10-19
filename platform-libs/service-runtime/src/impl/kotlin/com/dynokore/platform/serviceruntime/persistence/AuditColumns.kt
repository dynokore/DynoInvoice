package com.dynokore.platform.serviceruntime.persistence

import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.RecordContext
import org.jooq.RecordListener
import org.jooq.Table
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** A row write that breaks the audit-column rules (INV-REQ-854 b8); refused before the statement is built. */
class AuditColumnViolation(
    message: String,
) : IllegalStateException(message)

/**
 * The audit columns of a table, opt-in by name (INV-T-4224): `created_at`, `created_by`, `updated_at` and `updated_by`
 * together. A table with none of them is untouched; a table with only some of them is refused by the stores, because
 * half-stamped rows are never what a table declares.
 */
internal class AuditColumns private constructor(
    val createdAt: Field<*>,
    val createdBy: Field<*>,
    val updatedAt: Field<*>,
    val updatedBy: Field<*>,
) {
    companion object {
        const val CREATED_AT = "created_at"
        const val CREATED_BY = "created_by"
        const val UPDATED_AT = "updated_at"
        const val UPDATED_BY = "updated_by"
        private val NAMES = listOf(CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)

        /** The audit columns of [table], or null when it has none of the four names. */
        fun of(table: Table<*>): AuditColumns? {
            val byName = NAMES.associateWith { name -> table.fields().firstOrNull { it.name == name } }
            if (byName.values.all { it == null }) return null
            return AuditColumns(
                present(byName[CREATED_AT]),
                present(byName[CREATED_BY]),
                present(byName[UPDATED_AT]),
                present(byName[UPDATED_BY]),
            )
        }

        private fun present(field: Field<*>?): Field<*> = field ?: throw NullPointerException("audit column is missing")

        /** Why [table] has some but not all four audit columns, or null. */
        fun problem(table: Table<*>): String? {
            val present = NAMES.filter { name -> table.fields().any { it.name == name } }
            return if (present.isEmpty() || present.size == NAMES.size) {
                null
            } else {
                "table ${table.qualifiedName} has only some of the audit columns $NAMES (${present.joinToString()}): " +
                    "declare all four or none"
            }
        }
    }
}

/**
 * Sets the audit columns of a table that has them, from the service [Clock] and the transaction's bound actor
 * (INV-T-4224; INV-REQ-854 b8). Registered on the service's one `Configuration`, so it applies to every record insert
 * and update, including the convenience layer's.
 *
 * - Insert: `created_at` and `updated_at` are the clock's now, `created_by` and `updated_by` the bound actor. A value
 *   the caller set in `created_by` or `updated_by` is accepted only when it equals the actor. An insert with no bound
 *   actor is refused.
 * - Update: `updated_at` and `updated_by` are set. A changed `created_at` or `created_by`, or an `updated_by` that is
 * not
 *   the actor, is refused.
 */
class AuditColumnRecordListener(
    private val clock: Clock,
    private val tenants: TenantContext,
) : RecordListener {
    override fun insertStart(ctx: RecordContext) {
        val record = ctx.record()
        val audit = AuditColumns.of(tableOf(record) ?: return) ?: return
        stamp(record, audit, insert = true)
    }

    override fun updateStart(ctx: RecordContext) {
        val record = ctx.record()
        val audit = AuditColumns.of(tableOf(record) ?: return) ?: return
        stamp(record, audit, insert = false)
    }

    private fun tableOf(record: Record): Table<*>? = (record as? org.jooq.TableRecord<*>)?.getTable()

    private fun stamp(
        record: Record,
        audit: AuditColumns,
        insert: Boolean,
    ) {
        val actor =
            tenants.currentActor()?.takeIf { it.isNotBlank() }
                ?: throw AuditColumnViolation("a write to an audit-column table needs a bound actor (INV-REQ-854 b8)")
        if (insert) {
            refuseForeign(record, audit.createdBy, actor)
            refuseForeign(record, audit.updatedBy, actor)
            setTime(record, audit.createdAt)
            set(record, audit.createdBy, actor)
        } else {
            refuseChanged(record, audit.createdAt)
            refuseChanged(record, audit.createdBy)
            refuseForeign(record, audit.updatedBy, actor)
        }
        setTime(record, audit.updatedAt)
        set(record, audit.updatedBy, actor)
    }

    private fun refuseForeign(
        record: Record,
        field: Field<*>,
        actor: String,
    ) {
        val value = record.get(field)
        if (record.touched(field) && value != null && value != actor) {
            throw AuditColumnViolation(
                "${field.name} was set to '$value' but the bound actor is '$actor' (INV-REQ-854 b8)",
            )
        }
    }

    private fun refuseChanged(
        record: Record,
        field: Field<*>,
    ) {
        if (record.touched(field)) {
            throw AuditColumnViolation("${field.name} cannot change on update (INV-REQ-854 b8)")
        }
    }

    private fun setTime(
        record: Record,
        field: Field<*>,
    ) {
        val now: Any =
            when (field.type) {
                OffsetDateTime::class.java -> OffsetDateTime.now(clock)

                Instant::class.java -> Instant.now(clock)

                LocalDateTime::class.java -> LocalDateTime.now(clock)

                else -> throw AuditColumnViolation(
                    "${field.name} must be a timestamp column, not ${field.type.simpleName}",
                )
            }
        set(record, field, now)
    }

    private fun set(
        record: Record,
        field: Field<*>,
        value: Any,
    ) {
        if (!field.type.isInstance(value)) {
            throw AuditColumnViolation("${field.name} is ${field.type.simpleName}, not ${value.javaClass.simpleName}")
        }
        @Suppress("UNCHECKED_CAST")
        record.set(field as Field<Any?>, value)
    }
}
