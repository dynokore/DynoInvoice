package com.dynokore.gst.submission.adapters

import java.sql.Connection

/**
 * The catalogue facts of INV-AC-2180 and INV-REQ-1194 (coordinator ruling INV-Q-7266 (A)): where `work_queue` lives
 * and who holds a privilege on it. Every comparison is by equality on the exact names read from the catalogue; a
 * look-alike schema or role is a different name.
 */
object WorkQueueCatalogue {
    val EXPECTED_ROLES = setOf("submission_migrate", "submission_runtime", "submission_sys")

    private fun strings(
        connection: Connection,
        sql: String,
    ): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }

    /** Schemas holding a table (or partitioned table) named `work_queue`, outside the system schemas. */
    fun workQueueSchemas(connection: Connection): List<String> =
        strings(
            connection,
            """
            select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where c.relname = 'work_queue' and c.relkind in ('r', 'p')
              and n.nspname !~ '^pg_' and n.nspname <> 'information_schema'
            order by 1
            """.trimIndent(),
        )

    /** Schemas holding any relation named `queue_item` (INV-Q-7266: no such table is created). */
    fun queueItemSchemas(connection: Connection): List<String> =
        strings(
            connection,
            """
            select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where c.relname = 'queue_item' order by 1
            """.trimIndent(),
        )

    /**
     * Every role with any privilege on `submission.work_queue`: its owner, the grantees of the table ACL and the
     * grantees of any column ACL; PUBLIC shows as `PUBLIC`.
     */
    fun privilegedRoles(connection: Connection): Set<String> =
        strings(
            connection,
            """
            with t as (
                select c.oid, c.relowner, c.relacl from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'submission' and c.relname = 'work_queue' and c.relkind in ('r', 'p')
            ), grants as (
                select t.relowner as grantee from t
                union all
                select a.grantee from t cross join lateral aclexplode(coalesce(t.relacl, acldefault('r', t.relowner))) a
                union all
                select a.grantee from t join pg_attribute att on att.attrelid = t.oid and att.attacl is not null
                    cross join lateral aclexplode(att.attacl) a
            )
            select distinct coalesce(r.rolname, 'PUBLIC') from grants g left join pg_roles r on r.oid = g.grantee
            """.trimIndent(),
        ).toSet()

    /** The findings; empty when the schema layout and the grants are exactly those of INV-Q-7266 (A). */
    fun findings(connection: Connection): List<String> {
        val findings = mutableListOf<String>()
        val schemas = workQueueSchemas(connection)
        if (schemas != listOf("submission")) findings += "work_queue must exist once, in schema submission; found in $schemas"
        val queueItem = queueItemSchemas(connection)
        if (queueItem.isNotEmpty()) findings += "no relation named queue_item may exist; found in $queueItem"
        val roles = privilegedRoles(connection)
        if (roles != EXPECTED_ROLES) findings += "roles with a privilege on submission.work_queue are $roles, not $EXPECTED_ROLES"
        return findings
    }
}
