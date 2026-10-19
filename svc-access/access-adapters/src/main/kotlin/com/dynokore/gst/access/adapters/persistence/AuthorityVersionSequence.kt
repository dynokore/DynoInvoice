package com.dynokore.gst.access.adapters.persistence

import org.jooq.Sequence
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

internal const val ACCESS_SCHEMA = "access"
internal const val AUTHORITY_VERSION_SEQUENCE_NAME = "authority_version_seq"

/**
 * The sequence `access.authority_version_seq` of the migrations, named by hand because the code generator emits no
 * sequence class (INV-Q-7449 A); a test equates both names with the migration's `CREATE SEQUENCE` statement.
 */
internal val AUTHORITY_VERSION_SEQ: Sequence<Long> =
    DSL.sequence(DSL.name(ACCESS_SCHEMA, AUTHORITY_VERSION_SEQUENCE_NAME), SQLDataType.BIGINT)
