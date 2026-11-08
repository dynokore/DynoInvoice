package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.CONNECTION
import com.dynokore.gst.masterdata.application.connection.ConnectionStore
import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.EnvironmentTag
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. The connection records of the bound tenant. The tenant column is added by the tenancy-jooq listeners and row
 * level security is forced behind them; a record is inserted once and never updated. The record has no column that can
 * hold a secret: `credential_ref` is the secret-store key (INV-REQ-834).
 */
class JooqConnectionStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ConnectionStore {
    override fun find(connectionRef: String): ConnectionRecord? =
        dsl
            .selectFrom(CONNECTION)
            .where(CONNECTION.RECORD_REF.eq(connectionRef))
            .fetchOne()
            ?.let(::record)

    override fun findByServiceAndTag(
        externalService: String,
        environmentTag: EnvironmentTag,
    ): ConnectionRecord? =
        dsl
            .selectFrom(CONNECTION)
            .where(CONNECTION.EXTERNAL_SERVICE.eq(externalService), CONNECTION.ENVIRONMENT_TAG.eq(environmentTag.name))
            .fetchOne()
            ?.let(::record)

    override fun save(record: ConnectionRecord) {
        dsl
            .insertInto(CONNECTION)
            .set(CONNECTION.ID, ids.next(clock.instant()))
            .set(CONNECTION.RECORD_REF, record.connectionRef)
            .set(CONNECTION.EXTERNAL_SERVICE, record.externalService)
            .set(CONNECTION.ENDPOINT, record.endpoint)
            .set(CONNECTION.CREDENTIAL_REF, record.credentialRef)
            .set(CONNECTION.ENVIRONMENT_TAG, record.environmentTag.name)
            .set(CONNECTION.OWNER_SERVICE, record.ownerService)
            .set(CONNECTION.CREATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** The sequence is a named object, not plain SQL: the tenancy predicate guard refuses plain-SQL parts. */
    override fun nextRef(): String = "CON-" + dsl.nextval(REF_SEQUENCE)

    private fun record(row: org.jooq.Record): ConnectionRecord =
        ConnectionRecord(
            connectionRef = row.get(CONNECTION.RECORD_REF).column("record_ref"),
            externalService = row.get(CONNECTION.EXTERNAL_SERVICE).column("external_service"),
            endpoint = row.get(CONNECTION.ENDPOINT).column("endpoint"),
            credentialRef = row.get(CONNECTION.CREDENTIAL_REF).column("credential_ref"),
            environmentTag = EnvironmentTag.valueOf(row.get(CONNECTION.ENVIRONMENT_TAG).column("environment_tag")),
            ownerService = row.get(CONNECTION.OWNER_SERVICE).column("owner_service"),
        )

    private companion object {
        val REF_SEQUENCE = DSL.sequence(DSL.name("masterdata", "connection_ref_seq"), SQLDataType.BIGINT)
    }
}
