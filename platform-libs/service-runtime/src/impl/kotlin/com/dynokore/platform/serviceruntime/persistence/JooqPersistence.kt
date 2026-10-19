package com.dynokore.platform.serviceruntime.persistence

import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableRecord
import org.jooq.UpdatableRecord
import org.jooq.exception.DataChangedException
import java.util.Objects

/**
 * The convenience layer over the service's one `DSLContext` (INV-T-4224; owner ruling INV-Q-4214, coordinator ruling
 * INV-Q-4217): makes an [AggregateStore] or a [LedgerAppender] for a generated table. Every statement runs on [dsl],
 * the
 * tenancy-jooq configuration, inside the caller's `TransactionRunner` transaction; the layer holds no connection of its
 * own and renders no SQL text.
 *
 * @throws IllegalArgumentException from the constructor when [dsl] is not the service's configuration (see
 *   [PersistenceConfiguration.problem]).
 */
class PersistenceLayer(
    private val dsl: DSLContext,
    private val guards: PersistenceGuards,
) {
    init {
        PersistenceConfiguration.problem(dsl.configuration())?.let {
            throw IllegalArgumentException("the DSLContext cannot back the persistence layer: $it")
        }
    }

    /** An [AggregateStore] for [table] and its generated [pojo] type. */
    fun <R : UpdatableRecord<R>, P : Any, I : Any> aggregates(
        table: Table<R>,
        pojo: Class<P>,
    ): AggregateStore<P, I> = JooqAggregateStore(dsl, guards, table, pojo)

    /** A [LedgerAppender] for [table] and its generated [pojo] type. */
    fun <R : TableRecord<R>, P : Any> ledger(
        table: Table<R>,
        pojo: Class<P>,
    ): LedgerAppender<P> = JooqLedgerAppender(dsl, table, pojo)
}

private fun requireLayer(
    dsl: DSLContext,
    table: Table<*>,
) {
    PersistenceConfiguration.problem(dsl.configuration())?.let {
        throw IllegalArgumentException("the DSLContext cannot back the persistence layer: $it")
    }
    AuditColumns.problem(table)?.let { throw IllegalArgumentException(it) }
}

/**
 * The version rule (INV-Q-6770): with `updateRecordVersion` on, jOOQ stores a record's version + 1 on INSERT, so the
 * record is given `version - 1` (in the field's own Java type) and the row stores exactly the POJO's version. A table
 * without a jOOQ record version is inserted as it is. Every row is checked before any statement runs.
 */
private fun versionOf(
    type: Class<*>,
    value: Long,
): Any =
    when (type) {
        Long::class.javaObjectType -> value
        Int::class.javaObjectType -> Math.toIntExact(value)
        else -> throw IllegalArgumentException("the record version has the unsupported type ${type.name}")
    }

private fun <R : TableRecord<R>> insertRecords(
    dsl: DSLContext,
    table: Table<R>,
    pojos: List<Any>,
) {
    val versionField = table.recordVersion
    val records =
        pojos.map { pojo ->
            val record = dsl.newRecord(table, pojo)
            if (versionField != null) {
                val given = record.get(versionField)
                val number = (given as? Number)?.toLong() ?: 0L
                require(number >= 0) { "version must not be negative: $number" }
                @Suppress("UNCHECKED_CAST")
                record.set(versionField as Field<Any?>, versionOf(versionField.type, number - 1))
            }
            record
        }
    records.forEach { it.insert() }
}

internal class JooqAggregateStore<R : UpdatableRecord<R>, P : Any, I : Any>(
    private val dsl: DSLContext,
    guards: PersistenceGuards,
    private val table: Table<R>,
    private val pojo: Class<P>,
) : AggregateStore<P, I> {
    private val id: Field<I>
    private val version: Field<Number>

    init {
        requireLayer(dsl, table)
        guards.storeRefusal(table)?.let { throw IllegalArgumentException(it) }
        val key = table.primaryKey?.fields
        require(key != null && key.size == 1) { "table ${table.qualifiedName} needs a single-column primary key" }
        val versionField = table.fields().firstOrNull { it.name == VERSION }
        require(versionField != null && Number::class.java.isAssignableFrom(versionField.type)) {
            "table ${table.qualifiedName} has no numeric '$VERSION' column: use LedgerAppender or add one"
        }
        require(table.recordVersion?.name == VERSION) {
            "table ${table.qualifiedName}: '$VERSION' is not its jOOQ record version (generate it as the version field)"
        }
        @Suppress("UNCHECKED_CAST")
        id = key[0] as Field<I>

        @Suppress("UNCHECKED_CAST")
        version = versionField as Field<Number>
    }

    override fun load(id: I): P? =
        dsl
            .selectFrom(table)
            .where(this.id.eq(id))
            .fetchOne()
            ?.into(pojo)

    override fun insert(pojo: P) {
        insertRecords(dsl, table, listOf(pojo))
    }

    override fun insertAll(pojos: List<P>) {
        insertRecords(dsl, table, pojos)
    }

    override fun update(
        before: P,
        after: P,
    ): UpdateOutcome {
        val record = dsl.newRecord(table, before)
        val target = dsl.newRecord(table, after)
        require(Objects.equals(record.get(id), target.get(id))) { "update(before, after): the id differs" }
        record.touched(false) // the loaded values are the originals the record's version predicate is built from
        var differs = false
        for (field in table.fields()) {
            if (field == id || field.name == VERSION) continue
            if (!Objects.deepEquals(record.get(field), target.get(field))) {
                @Suppress("UNCHECKED_CAST")
                record.set(field as Field<Any?>, target.get(field))
                differs = true
            }
        }
        if (!differs) return UpdateOutcome.Unchanged
        return try {
            if (record.update() == 0) UpdateOutcome.Conflict else UpdateOutcome.Updated(record.get(version).toLong())
        } catch (_: DataChangedException) {
            UpdateOutcome.Conflict
        }
    }

    override fun delete(pojo: P): DeleteOutcome {
        val record = dsl.newRecord(table, pojo)
        record.touched(false)
        return try {
            if (record.delete() == 0) DeleteOutcome.Conflict else DeleteOutcome.Deleted
        } catch (_: DataChangedException) {
            DeleteOutcome.Conflict
        }
    }

    private companion object {
        const val VERSION = "version"
    }
}

internal class JooqLedgerAppender<R : TableRecord<R>, P : Any>(
    private val dsl: DSLContext,
    private val table: Table<R>,
    @Suppress("unused") private val pojo: Class<P>,
) : LedgerAppender<P> {
    init {
        requireLayer(dsl, table)
    }

    override fun append(pojo: P) {
        insertRecords(dsl, table, listOf(pojo))
    }

    override fun appendAll(pojos: List<P>) {
        insertRecords(dsl, table, pojos)
    }
}
