package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.application.document.CreateDocumentVersion
import com.dynokore.gst.canonical.application.document.DerivedTablesRebuild
import com.dynokore.gst.canonical.application.document.WindowReader
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.postgresql.ds.PGSimpleDataSource
import java.time.LocalDate
import java.util.UUID

/** The document use cases of canonical-application over the runtime login, bound per call to a tenant. */
class DocumentKit {
    private val tenants = TenantContextImpl()
    val transaction =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(CanonicalDb.jdbcUrl)
                user = CanonicalDb.RUNTIME
                password = CanonicalDb.RUNTIME_PASSWORD
            },
            tenants,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf() // the runner zeroes what it gets

                override fun previousKey(): ByteArray? = null
            },
        )
    private val tables =
        TenancyTables.declared(
            mapOf(
                DSL.name("canonical", "document") to TableClass.TENANT,
                DSL.name("canonical", "document_version") to TableClass.TENANT,
                DSL.name("canonical", "document_line") to TableClass.TENANT,
                DSL.name("canonical", "document_subtotal") to TableClass.TENANT,
                DSL.name("canonical", "document_party") to TableClass.TENANT,
            ),
        )
    private val dsl =
        TenancyJooq.configure(DefaultConfiguration().set(SQLDialect.POSTGRES), tables, tenants, transaction).dsl()
    val store = JooqDocumentStore(dsl)
    val versions = CreateDocumentVersion(store, transaction)
    val windows = WindowReader(store, transaction)
    val rebuilds = DerivedTablesRebuild(store, transaction)

    fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), SignedBindings.ACTOR, block)

    fun insert(document: NewDocument) =
        asTenant(document.tenantId) { transaction.inTransaction { store.insert(document) } }

    companion object {
        /** A canonical document of the contract shape with no line, no subtotal and both parties empty. */
        fun defaultCanonical(number: String) =
            """{"documentNumber":"$number","lines":{"value":[],"classification":"content"},""" +
                """"taxSubtotals":{"value":[],"classification":"content"},""" +
                """"parties":{"value":{"supplier":{},"buyer":{}},"classification":"content"}}"""

        fun version(
            on: LocalDate,
            canonical: String = defaultCanonical("n"),
        ) = NewVersion(
            canonical,
            ScopeVerdict.IN_SCOPE,
            "scope-1",
            "map-1",
            null,
            SpecificationVersion.PINT_SG,
            on,
            "svc",
        )

        @Suppress("LongParameterList") // a test builder: every argument has a default
        fun document(
            tenant: UUID,
            entity: UUID,
            on: LocalDate,
            number: String = "INV-" + UUID.randomUUID(),
            issuer: String = "issuer-1",
            docUuid: UUID = UUID.randomUUID(),
            canonical: String = defaultCanonical(number),
        ) = NewDocument(
            docUuid,
            tenant,
            entity,
            null,
            Direction.SALE,
            380,
            number,
            issuer,
            2026,
            null,
            DocumentState.READY,
            null,
            version(on, canonical),
        )
    }
}

/** Plain JDBC helpers over the Testcontainers database for the document tests. */
object DocumentSql {
    /** Runs [statement] as the superuser and returns the rows as lists of strings. */
    fun rows(
        statement: String,
        vararg args: Any,
    ): List<List<String?>> =
        CanonicalDb.superuser().use { connection ->
            connection.prepareStatement(statement).use { prepared ->
                args.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
                prepared.executeQuery().use { result ->
                    val width = result.metaData.columnCount
                    generateSequence { if (result.next()) (1..width).map { result.getString(it) } else null }.toList()
                }
            }
        }

    fun one(
        statement: String,
        vararg args: Any,
    ): String? = rows(statement, *args).single().single()

    /** [column] of the row of [doc] in `canonical.document`, as text. */
    fun documentColumn(
        column: String,
        doc: UUID,
    ): String? = one("select $column::text from canonical.document where doc_uuid = ?", doc)

    /** The INSERT of one document row with [number], for the runtime login. */
    fun insertOf(
        tenant: UUID,
        entity: UUID,
        number: String,
    ) = "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
        "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, current_version, " +
        "state) values ('${UUID.randomUUID()}', '$tenant', '$entity', 'SALE', 380, '$number', 'probe', 2026, " +
        "'2026-03-15', '2026-03-15', 1, 'READY')"

    fun execute(statement: String) =
        CanonicalDb.superuser().use { it.createStatement().use { s -> s.execute(statement) } }

    /** The SQL state of the refusal of [statement] as the runtime login bound to [tenant], or null when it ran. */
    fun runtimeRefusal(
        tenant: UUID,
        statement: String,
    ): String? =
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                connection.prepareStatement(statement).use { it.executeUpdate() }
                null
            } catch (refused: java.sql.SQLException) {
                refused.sqlState
            } finally {
                connection.rollback()
            }
        }

    /** The SQL state found anywhere in the cause chain of [failure]. */
    fun sqlState(failure: Throwable): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
}
