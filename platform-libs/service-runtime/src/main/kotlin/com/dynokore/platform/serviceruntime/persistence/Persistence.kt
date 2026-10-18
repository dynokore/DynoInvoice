package com.dynokore.platform.serviceruntime.persistence

/**
 * Simple aggregate CRUD for one table with a `version` column (INV-ADR-3968 items 4a and 4c; owner ruling INV-Q-4214,
 * coordinator ruling INV-Q-4217). [P] is the table's generated POJO, [I] the type of its single-column primary key.
 *
 * Every call runs inside an open `TransactionRunner` transaction with a tenant and actor binding; a call outside one is
 * refused before any SQL runs. The store sets no tenant predicate itself: the tenant limit is tenancy-jooq's, so a row
 * of another tenant is never loaded, updated or deleted.
 */
interface AggregateStore<P : Any, I : Any> {
    /** The row with this id of the bound tenant, or null. */
    fun load(id: I): P?

    /**
     * Inserts [pojo] as it is: the row stores the version it is given (a new aggregate is built with version 0), so the
     * caller's POJO is the `before` of its next update with no reload. A negative version is refused with
     * [IllegalArgumentException] before any statement (INV-Q-6770).
     */
    fun insert(pojo: P)

    /**
     * Inserts [pojos] in list order, one INSERT statement per row inside the caller's transaction; no batch. A refused
     * row stops the call and its exception reaches the caller, whose transaction then rolls back every row of the call
     * (a caller that catches the exception and commits keeps the rows inserted before it). An empty list runs nothing.
     * Each row stores the version it is given; every row is checked for a negative version before the first INSERT.
     */
    fun insertAll(pojos: List<P>)

    /**
     * Writes the columns whose values differ between [before] and [after], and `version + 1`, to the row with
     * [before]'s id and version. The stored version is `before.version + 1`; `after.version` is ignored. A
     * row that is not there with that version (changed meanwhile, deleted, or of another tenant) is a
     * [UpdateOutcome.Conflict]; nothing is retried (CS-54).
     */
    fun update(
        before: P,
        after: P,
    ): UpdateOutcome

    /** Deletes the row with [pojo]'s id and version; any other row state is a [DeleteOutcome.Conflict]. */
    fun delete(pojo: P): DeleteOutcome
}

/** The outcome of [AggregateStore.update]. */
sealed interface UpdateOutcome {
    /** The row was written; [version] is its new version. */
    data class Updated(
        val version: Long,
    ) : UpdateOutcome

    /** [AggregateStore.update] got equal `before` and `after`: no statement ran and the version was not checked. */
    data object Unchanged : UpdateOutcome

    /** The version in `before` is stale or the row is not visible to the bound tenant: nothing was changed. */
    data object Conflict : UpdateOutcome
}

/** The outcome of [AggregateStore.delete]. */
sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome

    /** The version is stale or the row is not visible to the bound tenant: nothing was deleted. */
    data object Conflict : DeleteOutcome
}

/**
 * Insert-only access to an append-only table (CS-22): it offers no update, delete or store of an existing record.
 * The same transaction and tenancy rules as [AggregateStore].
 */
interface LedgerAppender<P : Any> {
    /**
     * Appends one row. For a table with a jOOQ record version the row stores the version it is given; a negative
     * version is refused before any statement (INV-Q-6770).
     */
    fun append(pojo: P)

    /**
     * Appends [pojos] in list order, one INSERT statement per row; the same rules as [AggregateStore.insertAll]
     * (each row stores the version it is given).
     */
    fun appendAll(pojos: List<P>)
}
